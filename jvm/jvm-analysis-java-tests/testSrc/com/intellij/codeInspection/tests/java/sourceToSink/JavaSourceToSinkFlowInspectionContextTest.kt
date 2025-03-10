package com.intellij.codeInspection.tests.java.sourceToSink

import com.intellij.codeInspection.sourceToSink.SourceToSinkFlowInspection
import com.intellij.codeInspection.tests.sourceToSink.SourceToSinkFlowInspectionTestBase
import com.intellij.jvm.analysis.JavaJvmAnalysisTestUtil
import com.intellij.testFramework.TestDataPath

private const val INSPECTION_PATH = "/codeInspection/sourceToSinkFlow/context"
@TestDataPath("\$CONTENT_ROOT/testData$INSPECTION_PATH")
class JavaSourceToSinkFlowInspectionContextTest : SourceToSinkFlowInspectionTestBase() {

  override val inspection: SourceToSinkFlowInspection
    get() = super.inspection.apply {
      untaintedParameterWithPlaceMethodClass.add("java.io.PrintWriter")
      untaintedParameterWithPlaceMethodName.add("write")
      untaintedParameterWithPlaceIndex.add("0")
      untaintedParameterWithPlacePlaceClass.add("com.example.sqlinjection.Complete.HttpServletResponse")
      untaintedParameterWithPlacePlaceMethod.add("getWriter")
    }

  override fun getBasePath(): String {
    return JavaJvmAnalysisTestUtil.TEST_DATA_PROJECT_RELATIVE_BASE_PATH + INSPECTION_PATH
  }

  fun testSimple() {
    myFixture.testHighlighting("Simple.java")
  }
}