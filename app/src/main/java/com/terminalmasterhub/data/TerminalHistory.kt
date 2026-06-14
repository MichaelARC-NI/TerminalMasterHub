package com.terminalmasterhub.data

/**
 * Data class para almacenar el historial de comandos de la terminal.
 */
data class TerminalHistory(
    val commands: MutableList<String> = mutableListOf(),
    var currentIndex: Int = -1
) {
    fun addCommand(cmd: String) {
        commands.add(cmd)
        currentIndex = commands.size
    }

    fun getPrevious(): String? {
        if (commands.isEmpty()) return null
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        return commands.getOrNull(currentIndex)
    }

    fun getNext(): String? {
        if (commands.isEmpty()) return null
        currentIndex = (currentIndex + 1).coerceAtMost(commands.size)
        return if (currentIndex < commands.size) commands[currentIndex] else null
    }
}
