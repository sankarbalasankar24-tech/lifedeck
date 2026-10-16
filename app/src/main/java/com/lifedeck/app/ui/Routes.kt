package com.lifedeck.app.ui

/** All navigation destinations in one place. */
object Routes {
    const val TODAY = "today"
    const val INBOX = "inbox"
    const val PROJECTS = "projects"
    const val SHOPPING = "shopping"
    const val CALENDAR = "calendar"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val EVENING = "evening"
    const val TASK = "task/{taskId}"
    const val GOAL = "goal/{goalId}"
    const val PROJECT = "project/{projectId}"
    const val TRIP = "trip/{tripId}"
    const val MEMORY = "memory/{memoryId}"

    fun task(id: String) = "task/$id"
    fun goal(id: String) = "goal/$id"
    fun project(id: String) = "project/$id"
    fun trip(id: String) = "trip/$id"
    fun memory(id: String) = "memory/$id"

    val bottomTabs = listOf(PROJECTS, SHOPPING, INBOX, TODAY)
}
