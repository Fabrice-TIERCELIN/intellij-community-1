// "Import class 'Arrays'" "true"
// DISABLE-ERRORS

fun test() {
    val java = 42
    Arrays<caret>
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ImportFix