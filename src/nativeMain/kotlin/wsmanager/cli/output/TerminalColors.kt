package wsmanager.cli.output

/**
 * ANSI color codes for terminal output.
 */
object TerminalColors {
    const val RESET = "\u001B[0m"
    const val BOLD = "\u001B[1m"
    const val DIM = "\u001B[2m"
    const val UNDERLINE = "\u001B[4m"

    // Foreground colors
    const val RED = "\u001B[31m"
    const val GREEN = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val BLUE = "\u001B[34m"
    const val MAGENTA = "\u001B[35m"
    const val CYAN = "\u001B[36m"
    const val WHITE = "\u001B[37m"
    const val GRAY = "\u001B[90m"

    // Background colors
    const val BG_RED = "\u001B[41m"
    const val BG_GREEN = "\u001B[42m"
    const val BG_YELLOW = "\u001B[43m"

    fun red(text: String) = "$RED$text$RESET"
    fun green(text: String) = "$GREEN$text$RESET"
    fun yellow(text: String) = "$YELLOW$text$RESET"
    fun blue(text: String) = "$BLUE$text$RESET"
    fun cyan(text: String) = "$CYAN$text$RESET"
    fun magenta(text: String) = "$MAGENTA$text$RESET"
    fun gray(text: String) = "$GRAY$text$RESET"
    fun bold(text: String) = "$BOLD$text$RESET"
    fun dim(text: String) = "$DIM$text$RESET"
    fun boldGreen(text: String) = "$BOLD$GREEN$text$RESET"
    fun boldRed(text: String) = "$BOLD$RED$text$RESET"
    fun boldYellow(text: String) = "$BOLD$YELLOW$text$RESET"
    fun boldCyan(text: String) = "$BOLD$CYAN$text$RESET"
    fun boldBlue(text: String) = "$BOLD$BLUE$text$RESET"
}
