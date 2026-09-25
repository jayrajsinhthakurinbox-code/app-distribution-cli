package io.github.jayrajsinh.appdistribution.cli

/**
 * Whether the CLI can prompt the user on the terminal.
 *
 * The IDE plugin runs the CLI without a TTY and sets
 * APPDIST_NON_INTERACTIVE=1, in which case it must never block on
 * stdin and instead reports what it needs through [marker] lines the
 * plugin understands.
 */
object Interactive {

    val isInteractive: Boolean
        get() = System.getenv("APPDIST_NON_INTERACTIVE") != "1" &&
                System.console() != null

    /** Prints a machine-readable line for the IDE plugin. */
    fun marker(name: String, value: String = "") {
        println(
            if (value.isEmpty()) "[$name]" else "[$name] $value"
        )
        System.out.flush()
    }

    /** Progress update the IDE plugin shows in its progress bar. */
    fun progress(percentage: Int, message: String) {
        marker("APPDIST_PROGRESS", "$percentage|$message")
    }
}
