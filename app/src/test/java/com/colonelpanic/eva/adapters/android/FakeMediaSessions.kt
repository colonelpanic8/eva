package com.colonelpanic.eva.adapters.android

/** Plays back a session list per read, so a command can be answered by a different later state. */
class FakeMediaSessions(
    private val reads: MutableList<List<MediaSnapshot>>,
    private val granted: Boolean = true,
    private val volumeReport: VolumeReport? = VolumeReport(40, muted = false),
) : MediaSessionAccess {
    val sent = mutableListOf<Pair<String, MediaCommand>>()
    val buttons = mutableListOf<MediaCommand>()
    val searches = mutableListOf<Pair<String, String>>()
    var volumeChange: Pair<VolumeAction, Int?>? = null

    override fun observable() = granted

    override fun sessions(): List<MediaSnapshot> = if (reads.size > 1) reads.removeAt(0) else reads.first()

    override fun send(
        packageName: String,
        command: MediaCommand,
    ): Boolean {
        sent += packageName to command
        return sessions().any { it.packageName == packageName }
    }

    override fun playFromSearch(
        packageName: String,
        query: String,
    ): Boolean {
        searches += packageName to query
        return true
    }

    override fun sendMediaButton(command: MediaCommand): Boolean {
        buttons += command
        return true
    }

    override fun volume() = volumeReport

    override fun changeVolume(
        action: VolumeAction,
        percent: Int?,
    ): VolumeReport? {
        volumeChange = action to percent
        return volumeReport
    }
}
