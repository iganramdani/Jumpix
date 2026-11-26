package com.jumpropeai.ai

enum class BattleMode(val label: String, val durationSec: Int) {
    SPEED_DUEL_30S("30s Speed Duel", 30),
    LAST_JUMPER_STANDING("Last Jumper Standing", 180),
    SYNC_BATTLE("60s Sync Battle", 60)
}

data class BattleArenaState(
    val isActive: Boolean = false,
    val mode: BattleMode = BattleMode.SPEED_DUEL_30S,
    val player1Jumps: Int = 0,
    val player2Jumps: Int = 0,
    val player1Alive: Boolean = true,
    val player2Alive: Boolean = true,
    val activeSlot: Int = 1,
    val winnerLabel: String? = null
)

/**
 * Jumpvex v5.0 — 1-Phone 2-Player Interleaved Split-Screen Battle Arena (Zero-GC).
 *
 * Uses Time-Division Frame Interleaving (Odd frame -> Left ROI Player 1, Even frame -> Right ROI
 * Player 2) so budget phones like Redmi Note 9 run 2 simultaneous athletes at the EXACT same
 * single-player ML Kit CPU cost!
 */
class SplitBattleArenaEngine {

    private val p1Detector = JumpDetector()
    private val p2Detector = JumpDetector()

    private var frameParity = 0
    private var p1Jumps = 0
    private var p2Jumps = 0
    private var p1LastJumpMs = -10_000L
    private var p2LastJumpMs = -10_000L
    private var p1Alive = true
    private var p2Alive = true
    private var mode = BattleMode.SPEED_DUEL_30S
    private var active = false

    fun start(battleMode: BattleMode) {
        mode = battleMode
        active = true
        frameParity = 0
        p1Jumps = 0
        p2Jumps = 0
        p1LastJumpMs = -10_000L
        p2LastJumpMs = -10_000L
        p1Alive = true
        p2Alive = true
        p1Detector.reset()
        p2Detector.reset()
    }

    fun stop() {
        active = false
    }

    /**
     * Returns which half-frame ROI (`1` = Left Player 1, `2` = Right Player 2) should be cropped
     * for the next camera frame, toggling every frame.
     */
    fun nextInterleavedPlayerSlot(): Int {
        frameParity = (frameParity + 1) and 1
        return if (frameParity == 1) 1 else 2
    }

    fun processPlayerFrame(playerSlot: Int, poseFrame: PoseFrame, elapsedMs: Long): BattleArenaState {
        if (!active) return BattleArenaState()

        if (playerSlot == 1 && p1Alive) {
            val res = p1Detector.processFrame(poseFrame, isCountingActive = true)
            if (res.jumpDetected) {
                p1Jumps++
                p1LastJumpMs = poseFrame.timestampMs
            }
            if (mode == BattleMode.LAST_JUMPER_STANDING && p1Jumps >= 3 &&
                (poseFrame.timestampMs - p1LastJumpMs) > 2_400L
            ) {
                p1Alive = false
            }
        } else if (playerSlot == 2 && p2Alive) {
            val res = p2Detector.processFrame(poseFrame, isCountingActive = true)
            if (res.jumpDetected) {
                p2Jumps++
                p2LastJumpMs = poseFrame.timestampMs
            }
            if (mode == BattleMode.LAST_JUMPER_STANDING && p2Jumps >= 3 &&
                (poseFrame.timestampMs - p2LastJumpMs) > 2_400L
            ) {
                p2Alive = false
            }
        }

        val timeUp = elapsedMs >= mode.durationSec * 1000L
        val winner = when {
            mode == BattleMode.LAST_JUMPER_STANDING && !p1Alive && p2Alive -> "Player 2 Wins! (Last Standing)"
            mode == BattleMode.LAST_JUMPER_STANDING && !p2Alive && p1Alive -> "Player 1 Wins! (Last Standing)"
            timeUp -> when {
                p1Jumps > p2Jumps -> "Player 1 Wins ($p1Jumps vs $p2Jumps)"
                p2Jumps > p1Jumps -> "Player 2 Wins ($p2Jumps vs $p1Jumps)"
                else -> "Draw! ($p1Jumps jumps each)"
            }
            else -> null
        }
        if (winner != null) active = false

        return BattleArenaState(
            isActive = active,
            mode = mode,
            player1Jumps = p1Jumps,
            player2Jumps = p2Jumps,
            player1Alive = p1Alive,
            player2Alive = p2Alive,
            activeSlot = playerSlot,
            winnerLabel = winner
        )
    }
}
