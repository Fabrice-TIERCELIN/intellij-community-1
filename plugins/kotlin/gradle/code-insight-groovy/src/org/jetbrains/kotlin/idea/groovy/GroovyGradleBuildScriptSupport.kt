// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.groovy

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ExternalLibraryDescriptor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.idea.base.codeInsight.CliArgumentStringBuilder.buildArgumentString
import org.jetbrains.kotlin.idea.base.codeInsight.CliArgumentStringBuilder.replaceLanguageFeature
import org.jetbrains.kotlin.idea.base.externalSystem.KotlinGradleFacade
import org.jetbrains.kotlin.idea.base.util.module
import org.jetbrains.kotlin.idea.base.util.reformat
import org.jetbrains.kotlin.idea.compiler.configuration.IdeKotlinVersion
import org.jetbrains.kotlin.idea.configuration.*
import org.jetbrains.kotlin.idea.gradleCodeInsightCommon.*
import org.jetbrains.kotlin.idea.groovy.inspections.DifferentKotlinGradleVersionInspection
import org.jetbrains.kotlin.idea.projectConfiguration.RepositoryDescription
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.tools.projectWizard.Versions
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrApplicationStatement
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrAssignmentExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.path.GrMethodCallExpression
import org.jetbrains.plugins.groovy.lang.psi.api.util.GrStatementOwner

internal class GroovyGradleBuildScriptSupport : GradleBuildScriptSupport {
    override fun createManipulator(file: PsiFile, preferNewSyntax: Boolean): GroovyBuildScriptManipulator? {
        if (file !is GroovyFile) {
            return null
        }

        return GroovyBuildScriptManipulator(file, preferNewSyntax)
    }

    override fun createScriptBuilder(file: PsiFile): SettingsScriptBuilder<*>? {
        return if (file is GroovyFile) GroovySettingsScriptBuilder(file) else null
    }
}

class GroovyBuildScriptManipulator(
    override val scriptFile: GroovyFile,
    override val preferNewSyntax: Boolean
) : GradleBuildScriptManipulator<GroovyFile> {
    override fun isApplicable(file: PsiFile) = file is GroovyFile

    private val gradleVersion = GradleVersionProvider.fetchGradleVersion(scriptFile)

    override fun isConfiguredWithOldSyntax(kotlinPluginName: String): Boolean {
        val fileText = runReadAction { scriptFile.text }
        return containsDirective(fileText, getApplyPluginDirective(kotlinPluginName)) &&
                fileText.contains("org.jetbrains.kotlin") &&
                fileText.contains("kotlin-stdlib")
    }

    override fun isConfigured(kotlinPluginExpression: String): Boolean {
        val fileText = runReadAction { scriptFile.text }
        val pluginsBlockText = runReadAction { scriptFile.getBlockByName("plugins")?.text ?: "" }
        return (containsDirective(pluginsBlockText, kotlinPluginExpression)) &&
                fileText.contains("org.jetbrains.kotlin") &&
                fileText.contains("kotlin-stdlib")
    }

    override fun PsiElement.getAllVariableStatements(variableName: String): List<PsiElement> {
        val assignments = PsiTreeUtil.findChildrenOfType(this, GrAssignmentExpression::class.java)
            .filter { it.lValue.text.contains(variableName) }

        val setExpressions = PsiTreeUtil.findChildrenOfType(this, GrMethodCallExpression::class.java)
            .filter {
                (it.invokedExpression.text.contains("$variableName.set") || it.invokedExpression.text == "set${variableName.capitalize()}")
                        && it.expressionArguments.size == 1
            }

        return (assignments + setExpressions).sortedBy { it.startOffset }
    }

    override fun PsiElement.findParentBlock(name: String): PsiElement? {
        val parent = PsiTreeUtil.findFirstParent(this) { elem ->
            elem is GrMethodCallExpression && elem.invokedExpression.text.contains(name)
        } as? GrMethodCallExpression ?: return null
        return parent.closureArguments.getOrNull(0) ?: parent.invokedExpression
    }

    override fun configureBuildScripts(
        kotlinPluginName: String,
        kotlinPluginExpression: String,
        stdlibArtifactName: String,
        addVersion: Boolean,
        version: IdeKotlinVersion,
        jvmTarget: String?,
        changedFiles: ChangedConfiguratorFiles
    ) {
        changedFiles.storeOriginalFileContent(scriptFile)

        val useNewSyntax = useNewSyntax(kotlinPluginName, gradleVersion)
        if (useNewSyntax) {
            scriptFile
                .getPluginsBlock()
                .addLastExpressionInBlockIfNeeded(
                    if (addVersion) {
                        "$kotlinPluginExpression version '${version.artifactVersion}'"
                    } else kotlinPluginExpression
                )
            scriptFile.getRepositoriesBlock().apply {
                val repository = getRepositoryForVersion(version)
                val gradleFacade = KotlinGradleFacade.getInstance()
                if (repository != null && gradleFacade != null) {
                    scriptFile.module?.getBuildScriptSettingsPsiFile()?.let {
                        changedFiles.storeOriginalFileContent(it)
                        with(GradleBuildScriptSupport.getManipulator(it)) {
                            addPluginRepository(repository)
                            addMavenCentralPluginRepository()
                            addPluginRepository(DEFAULT_GRADLE_PLUGIN_REPOSITORY)
                        }
                    }
                }
            }
        } else {
            val applyPluginDirective = getGroovyApplyPluginDirective(kotlinPluginName)
            if (!containsDirective(scriptFile.text, applyPluginDirective)) {
                val apply = GroovyPsiElementFactory.getInstance(scriptFile.project).createExpressionFromText(applyPluginDirective)
                val applyStatement = getApplyStatement(scriptFile)
                if (applyStatement != null) {
                    scriptFile.addAfter(apply, applyStatement)
                } else {
                    val anchorBlock = scriptFile.getBlockByName("plugins") ?: scriptFile.getBlockByName("buildscript")
                    if (anchorBlock != null) {
                        scriptFile.addAfter(apply, anchorBlock.parent)
                    } else {
                        scriptFile.addAfter(apply, scriptFile.statements.lastOrNull() ?: scriptFile.firstChild)
                    }
                }
            }
        }

        scriptFile.getRepositoriesBlock().apply {
            addRepository(version)
            addMavenCentralIfMissing()
        }

        scriptFile.getDependenciesBlock().apply {
            addExpressionOrStatementInBlockIfNeeded(
                getGroovyDependencySnippet(stdlibArtifactName, !useNewSyntax, gradleVersion),
                isStatement = false,
                isFirst = false
            )
        }

        scriptFile.configureToolchainOrKotlinOptions(jvmTarget, version, gradleVersion, changedFiles)

    }

    override fun configureProjectBuildScript(kotlinPluginName: String, version: IdeKotlinVersion): Boolean {
        if (useNewSyntax(kotlinPluginName, gradleVersion)) return false

        val oldText = scriptFile.text
        scriptFile.apply {
            getBuildScriptBlock().apply {
                addFirstExpressionInBlockIfNeeded(VERSION.replace(VERSION_TEMPLATE, version.rawVersion))
            }

            getBuildScriptRepositoriesBlock().apply {
                addRepository(version)
                addMavenCentralIfMissing()
            }

            getBuildScriptDependenciesBlock().apply {
                addLastExpressionInBlockIfNeeded(CLASSPATH)
            }
        }

        return oldText != scriptFile.text
    }

    override fun getKotlinVersionFromBuildScript(): IdeKotlinVersion? {
        return DifferentKotlinGradleVersionInspection.getKotlinPluginVersion(scriptFile)
    }

    override fun findAndRemoveKotlinVersionFromBuildScript(): Boolean {
        val pluginsBlock = scriptFile.getBlockByName("plugins")
        return pluginsBlock?.let {
            pluginsBlock.findAndRemoveVersionExpressionInPluginsGroup("id 'org.jetbrains.kotlin.jvm'") ||
                    pluginsBlock.findAndRemoveVersionExpressionInPluginsGroup("id \"org.jetbrains.kotlin.jvm\"")
        } ?: false
    }

    private fun GrClosableBlock.findAndRemoveVersionExpressionInPluginsGroup(pluginName: String): Boolean {
        getChildrenOfType<GrStatement>().forEach {
            val textWithoutWhitespaces = it.text.filter { letter -> !letter.isWhitespace() }
            val pluginNameWithoutWhitespaces = pluginName.filter { letter -> !letter.isWhitespace() }
            if (textWithoutWhitespaces.contains(pluginNameWithoutWhitespaces) && textWithoutWhitespaces.contains("version")) {
                val psiFactory = GroovyPsiElementFactory.getInstance(project)
                val newStatement = psiFactory.createStatementFromText(pluginName)
                it.replace(newStatement)
                return true
            }
        }
        return false
    }

    override fun configureSettingsFile(kotlinPluginName: String, version: IdeKotlinVersion): Boolean {
        val originalText = scriptFile.text
        scriptFile.getBlockOrPrepend("pluginManagement").getBlockOrCreate("plugins").addLastExpressionInBlockIfNeeded(
            "$kotlinPluginName version '${version.artifactVersion}'"
        )
        return originalText != scriptFile.text
    }


    override fun changeLanguageFeatureConfiguration(
        feature: LanguageFeature,
        state: LanguageFeature.State,
        forTests: Boolean
    ): PsiElement? {
        if (usesNewMultiplatform()) {
            state.assertApplicableInMultiplatform()
            val kotlinBlock = scriptFile.getKotlinBlock()
            val sourceSetsBlock = kotlinBlock.getSourceSetsBlock()
            val allBlock = sourceSetsBlock.getBlockOrCreate("all")
            allBlock.addLastExpressionInBlockIfNeeded("languageSettings.enableLanguageFeature(\"${feature.name}\")")
            return allBlock.statements.lastOrNull()
        }

        val kotlinVersion = DifferentKotlinGradleVersionInspection.getKotlinPluginVersion(scriptFile)
        val featureArgumentString = feature.buildArgumentString(state, kotlinVersion)
        val parameterName = "freeCompilerArgs"
        return addOrReplaceKotlinTaskParameter(
            scriptFile,
            parameterName,
            "[\"$featureArgumentString\"]",
            forTests
        ) { insideKotlinOptions ->
            val prefix = if (insideKotlinOptions) "kotlinOptions." else ""
            val newText = text.replaceLanguageFeature(
                feature,
                state,
                kotlinVersion,
                prefix = "$prefix$parameterName = [",
                postfix = "]"
            )
            replaceWithStatementFromText(newText)
        }
    }

    override fun changeLanguageVersion(version: String, forTests: Boolean): PsiElement? =
        changeKotlinTaskParameter("languageVersion", version, forTests)

    override fun changeApiVersion(version: String, forTests: Boolean): PsiElement? =
        changeKotlinTaskParameter("apiVersion", version, forTests)

    override fun addKotlinLibraryToModuleBuildScript(
        targetModule: Module?,
        scope: DependencyScope,
        libraryDescriptor: ExternalLibraryDescriptor
    ) {
        val dependencyString = String.format(
            "%s \"%s:%s:%s\"",
            scope.toGradleCompileScope(scriptFile.isAndroidModule()),
            libraryDescriptor.libraryGroupId,
            libraryDescriptor.libraryArtifactId,
            libraryDescriptor.maxVersion
        )

        if (targetModule != null && usesNewMultiplatform()) {
            scriptFile
                .getKotlinBlock()
                .getSourceSetsBlock()
                .getBlockOrCreate(targetModule.name.takeLastWhile { it != '.' })
                .getDependenciesBlock()
                .addLastExpressionInBlockIfNeeded(dependencyString)
        } else {
            scriptFile.getDependenciesBlock().apply {
                addLastExpressionInBlockIfNeeded(dependencyString)
            }
        }
    }

    override fun getKotlinStdlibVersion(): String? {
        val versionProperty = "\$kotlin_version"
        scriptFile.getBlockByName("buildScript")?.let {
            if (it.text.contains("ext.kotlin_version = ")) {
                return versionProperty
            }
        }

        val dependencies = scriptFile.getBlockByName("dependencies")?.statements
        val stdlibArtifactPrefix = "org.jetbrains.kotlin:kotlin-stdlib:"
        dependencies?.forEach { dependency ->
            val dependencyText = dependency.text
            val startIndex = dependencyText.indexOf(stdlibArtifactPrefix) + stdlibArtifactPrefix.length
            val endIndex = dependencyText.length - 1
            if (startIndex != -1 && endIndex != -1) {
                return dependencyText.substring(startIndex, endIndex)
            }
        }

        return null
    }

    override fun addFoojayPlugin(changedFiles: ChangedConfiguratorFiles) {
        val settingsFile = scriptFile.module?.let {
            it.getTopLevelBuildScriptSettingsPsiFile() as? GroovyFile
        } ?: return

        if (!settingsFile.canBeConfigured()) {
            return
        }

        changedFiles.storeOriginalFileContent(settingsFile)
        val pluginBlock = settingsFile.getSettingsPluginsBlock()
        if (pluginBlock.text.contains(FOOJAY_RESOLVER_NAME)) return
        val foojayVersion = Versions.GRADLE_PLUGINS.FOOJAY_VERSION
        pluginBlock.addLastStatementInBlockIfNeeded("id '$FOOJAY_RESOLVER_CONVENTION_NAME' version '$foojayVersion'")
    }

    private fun addPluginRepositoryExpression(expression: String) {
        scriptFile
            .getBlockOrPrepend("pluginManagement")
            .getBlockOrCreate("repositories")
            .addLastExpressionInBlockIfNeeded(expression)
    }

    override fun addMavenCentralPluginRepository() {
        addPluginRepositoryExpression("mavenCentral()")
    }

    override fun addPluginRepository(repository: RepositoryDescription) {
        addPluginRepositoryExpression(repository.toGroovyRepositorySnippet())
    }

    override fun addResolutionStrategy(pluginId: String) {
        scriptFile
            .getBlockOrPrepend("pluginManagement")
            .getBlockOrCreate("resolutionStrategy")
            .getBlockOrCreate("eachPlugin")
            .addLastStatementInBlockIfNeeded(
                """
                    if (requested.id.id == "$pluginId") {
                        useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}{requested.version}")
                    }
                """.trimIndent()
            )
    }

    override fun addKotlinToolchain(targetVersionNumber: String) {
        scriptFile.getKotlinBlock()
            .addFirstExpressionInBlockIfNeeded("jvmToolchain($targetVersionNumber)")
    }

    override fun addKotlinExtendedDslToolchain(targetVersionNumber: String) {
        scriptFile.getKotlinBlock().getBlockOrCreate("jvmToolchain")
            .addFirstExpressionInBlockIfNeeded("languageVersion = JavaLanguageVersion.of($targetVersionNumber)")
    }

    private fun GrClosableBlock.addParameterAssignment(
        parameterName: String,
        defaultValue: String,
        replaceIt: GrStatement.(Boolean) -> GrStatement
    ) {
        statements.firstOrNull { stmt ->
            (stmt as? GrAssignmentExpression)?.lValue?.text == parameterName
        }?.let { stmt ->
            stmt.replaceIt(false)
        } ?: addLastExpressionInBlockIfNeeded("$parameterName = $defaultValue")
    }

    private fun String.extractTextFromQuotes(quoteCharacter: Char): String? {
        val quoteIndex = indexOf(quoteCharacter)
        if (quoteIndex != -1) {
            val lastQuoteIndex = lastIndexOf(quoteCharacter)
            return if (lastQuoteIndex > quoteIndex) substring(quoteIndex + 1, lastQuoteIndex) else null
        }
        return null
    }

    private fun String.extractTextFromQuotes(): String = extractTextFromQuotes('\'') ?: extractTextFromQuotes('"') ?: this

    private fun addOrReplaceKotlinTaskParameter(
        gradleFile: GroovyFile,
        parameterName: String,
        defaultValue: String,
        forTests: Boolean,
        replaceIt: GrStatement.(Boolean) -> GrStatement
    ): PsiElement? {
        if (usesNewMultiplatform()) {
            val kotlinBlock = gradleFile.getKotlinBlock()
            val kotlinTargets = kotlinBlock.getBlockOrCreate("targets")
            val targetNames = mutableListOf<String>()

            fun GrStatement.handleTarget(targetExpectedText: String) {
                if (this is GrMethodCallExpression && invokedExpression.text == targetExpectedText) {
                    val targetNameArgument = argumentList.expressionArguments.getOrNull(1)?.text
                    if (targetNameArgument != null) {
                        targetNames += targetNameArgument.extractTextFromQuotes()
                    }
                }
            }

            for (target in kotlinTargets.statements) {
                target.handleTarget("fromPreset")
            }
            for (target in kotlinBlock.statements) {
                target.handleTarget("targets.fromPreset")
            }

            val configureBlock = kotlinTargets.getBlockOrCreate("configure")
            val factory = GroovyPsiElementFactory.getInstance(kotlinTargets.project)
            val argumentList = factory.createArgumentListFromText(
                targetNames.joinToString(prefix = "([", postfix = "])", separator = ", ")
            )
            configureBlock.getStrictParentOfType<GrMethodCallExpression>()!!.argumentList.replaceWithArgumentList(argumentList)

            val kotlinOptions = configureBlock.getBlockOrCreate("tasks.getByName(compilations.main.compileKotlinTaskName).kotlinOptions")
            kotlinOptions.addParameterAssignment(parameterName, defaultValue, replaceIt)
            return kotlinOptions.parent.parent
        }

        val kotlinBlock: GrClosableBlock =
            if (gradleFile.isAndroidModule() && gradleFile.getBlockByName("android") != null) {
                gradleFile.getBlockOrCreate("tasks.withType(org.jetbrains.kotlin.gradle.tasks.${if (forTests) "KotlinTest" else "KotlinCompile"}).all")
            } else {
                gradleFile.getBlockOrCreate(if (forTests) "compileTestKotlin" else "compileKotlin")
            }

        for (stmt in kotlinBlock.statements) {
            if ((stmt as? GrAssignmentExpression)?.lValue?.text == "kotlinOptions.$parameterName") {
                return stmt.replaceIt(true)
            }
        }

        kotlinBlock.getBlockOrCreate("kotlinOptions").addParameterAssignment(parameterName, defaultValue, replaceIt)

        return kotlinBlock.parent
    }

    override fun changeKotlinTaskParameter(
        parameterName: String,
        parameterValue: String,
        forTests: Boolean
    ): PsiElement? {
        return addOrReplaceKotlinTaskParameter(
            scriptFile, parameterName, "\"$parameterValue\"", forTests
        ) { insideKotlinOptions ->
            if (insideKotlinOptions) {
                replaceWithStatementFromText("kotlinOptions.$parameterName = \"$parameterValue\"")
            } else {
                replaceWithStatementFromText("$parameterName = \"$parameterValue\"")
            }
        }
    }

    private fun getGroovyDependencySnippet(
        artifactName: String,
        withVersion: Boolean,
        gradleVersion: GradleVersionInfo
    ): String {
        val configuration = gradleVersion.scope("implementation")
        return "$configuration \"org.jetbrains.kotlin:$artifactName${if (withVersion) ":\$kotlin_version" else ""}\""
    }

    private fun getApplyPluginDirective(pluginName: String) = "apply plugin: '$pluginName'"

    private fun containsDirective(fileText: String, directive: String): Boolean {
        return fileText.contains(directive)
                || fileText.contains(directive.replace("\"", "'"))
                || fileText.contains(directive.replace("'", "\""))
    }

    private fun getApplyStatement(file: GroovyFile): GrApplicationStatement? =
        file.getChildrenOfType<GrApplicationStatement>().find { it.invokedExpression.text == "apply" }

    private fun GrClosableBlock.addRepository(version: IdeKotlinVersion): Boolean {
        val repository = getRepositoryForVersion(version)
        val snippet = when {
            repository != null -> repository.toGroovyRepositorySnippet()
            !isRepositoryConfigured(text) -> "$MAVEN_CENTRAL\n"
            else -> return false
        }
        return addLastExpressionInBlockIfNeeded(snippet)
    }

    private fun GroovyFile.isAndroidModule() = module?.buildSystemType == BuildSystemType.AndroidGradle

    private fun GrStatementOwner.getBuildScriptBlock() = getBlockOrCreate("buildscript") { newBlock ->
        val pluginsBlock = getBlockByName("plugins") ?: return@getBlockOrCreate false
        addBefore(newBlock, pluginsBlock.parent)
        true
    }

    private fun GrStatementOwner.getBuildScriptRepositoriesBlock(): GrClosableBlock =
        getBuildScriptBlock().getRepositoriesBlock()

    private fun GrStatementOwner.getBuildScriptDependenciesBlock(): GrClosableBlock =
        getBuildScriptBlock().getDependenciesBlock()

    private fun GrClosableBlock.addMavenCentralIfMissing(): Boolean =
        if (!isRepositoryConfigured(text)) addLastExpressionInBlockIfNeeded(MAVEN_CENTRAL) else false

    private fun GrStatementOwner.getRepositoriesBlock() = getBlockOrCreate("repositories")

    private fun GrStatementOwner.getDependenciesBlock(): GrClosableBlock = getBlockOrCreate("dependencies")

    private fun GrStatementOwner.getKotlinBlock(): GrClosableBlock = getBlockOrCreate("kotlin")

    private fun GrStatementOwner.getSourceSetsBlock(): GrClosableBlock = getBlockOrCreate("sourceSets")

    private fun GrClosableBlock.addOrReplaceExpression(snippet: String, predicate: (GrStatement) -> Boolean) {
        statements.firstOrNull(predicate)?.let { stmt ->
            stmt.replaceWithStatementFromText(snippet)
            return
        }
        addLastExpressionInBlockIfNeeded(snippet)
    }

    private fun getGroovyApplyPluginDirective(pluginName: String) = "apply plugin: '$pluginName'"

    private fun GrStatement.replaceWithStatementFromText(snippet: String): GrStatement {
        val newStatement = GroovyPsiElementFactory.getInstance(project).createExpressionFromText(snippet)
        newStatement.reformat()
        return replaceWithStatement(newStatement)
    }

    companion object {
        private const val VERSION_TEMPLATE = "\$VERSION$"
        private val VERSION = String.format("ext.kotlin_version = '%s'", VERSION_TEMPLATE)
        private const val GRADLE_PLUGIN_ID = "kotlin-gradle-plugin"
        private val CLASSPATH = "classpath \"$KOTLIN_GROUP_ID:$GRADLE_PLUGIN_ID:\$kotlin_version\""

        private fun PsiElement.getBlockByName(name: String): GrClosableBlock? {
            return getChildrenOfType<GrMethodCallExpression>()
                .filter { it.closureArguments.isNotEmpty() }
                .find { it.invokedExpression.text == name }
                ?.let { it.closureArguments[0] }
        }

        fun GrStatementOwner.getBlockOrCreate(
            name: String,
            customInsert: GrStatementOwner.(newBlock: PsiElement) -> Boolean = { false }
        ): GrClosableBlock {
            var block = getBlockByName(name)
            if (block == null) {
                val factory = GroovyPsiElementFactory.getInstance(project)
                val newBlock = factory.createExpressionFromText("$name{\n}\n")
                if (!customInsert(newBlock)) {
                    addAfter(newBlock, statements.lastOrNull() ?: firstChild)
                }
                block = getBlockByName(name)!!
            }
            return block
        }

        fun GrStatementOwner.getBlockOrPrepend(name: String) = getBlockOrCreate(name) { newBlock ->
            addAfter(newBlock, null)
            true
        }

        fun GrStatementOwner.getPluginsBlock() = getBlockOrCreate("plugins") { newBlock ->
            addAfter(newBlock, getBlockByName("buildscript"))
            true
        }

        fun GrStatementOwner.getSettingsPluginsBlock() = getBlockOrCreate("plugins") { newBlock ->
            val beforeBlock = getBlockByName("buildscript") ?: getBlockByName("pluginManagement")
            addAfter(newBlock, beforeBlock?.parent)
            true
        }

        fun GrClosableBlock.addLastExpressionInBlockIfNeeded(expressionText: String): Boolean =
            addExpressionOrStatementInBlockIfNeeded(expressionText, isStatement = false, isFirst = false)

        fun GrClosableBlock.addLastStatementInBlockIfNeeded(expressionText: String): Boolean =
            addExpressionOrStatementInBlockIfNeeded(expressionText, isStatement = true, isFirst = false)

        private fun GrClosableBlock.addFirstExpressionInBlockIfNeeded(expressionText: String): Boolean =
            addExpressionOrStatementInBlockIfNeeded(expressionText, isStatement = false, isFirst = true)

        private fun GrClosableBlock.addExpressionOrStatementInBlockIfNeeded(text: String, isStatement: Boolean, isFirst: Boolean): Boolean {
            if (statements.any { StringUtil.equalsIgnoreWhitespaces(it.text, text) }) return false
            val psiFactory = GroovyPsiElementFactory.getInstance(project)
            val newStatement = if (isStatement) psiFactory.createStatementFromText(text) else psiFactory.createExpressionFromText(text)
            newStatement.reformat()
            if (!isFirst && statements.isNotEmpty()) {
                val lastStatement = statements[statements.size - 1]
                if (lastStatement != null) {
                    addAfter(newStatement, lastStatement)
                }
            } else {
                if (firstChild != null) {
                    addAfter(newStatement, firstChild)
                }
            }
            return true
        }
    }
}
