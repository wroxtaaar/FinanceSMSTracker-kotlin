package com.example.financesmstracker

import android.content.SharedPreferences

/**
 * Phone-only credit-card settings (never sent to the server): bill due day, credit limit and
 * shared-limit groups.
 *
 * Some banks give two or more cards one combined limit (for example Axis Ace and Airtel Axis).
 * Cards in the same group have a "group_<cardId>" entry with the same group id, and the limit is
 * stored ONCE under that group ("limit_<groupId>"). Cards that are not shared keep their own
 * limit under "limit_<cardId>", exactly as before, so existing saved limits keep working.
 */
internal class CardMeta(private val prefs: SharedPreferences) {

    fun groupOf(id: String): String? = prefs.getString("group_$id", null)

    /** The limit that applies to [id]: the shared group's limit, or its own. 0 = not set. */
    fun limitMinor(id: String): Long = prefs.getLong("limit_" + (groupOf(id) ?: id), 0L)

    /** Bill due day 1..31, or 0 when not set. */
    fun dueDay(id: String): Int = prefs.getInt("due_$id", 0).takeIf { it in 1..31 } ?: 0

    /** Every card id sharing a limit with [id], including [id]. Just [id] when it is not shared. */
    fun sharedWith(id: String, allIds: Collection<String>): Set<String> {
        val group = groupOf(id) ?: return setOf(id)
        return (allIds.filter { groupOf(it) == group } + id).toSet()
    }

    /**
     * Save one card's settings. [shareWith] are the OTHER cards that share this card's limit
     * (empty = this card has its own limit). The group is kept consistent: removing a card from a
     * group releases it with the group's old limit, and a group left with one card is dissolved.
     */
    fun save(id: String, limitMinor: Long, dueDay: Int, shareWith: Set<String>, allIds: Collection<String>) {
        val all = allIds.toSet() + id
        val groups = all.associateWith { groupOf(it) }.toMutableMap()
        val limits = mutableMapOf<String, Long>()

        val oldGroup = groups[id]
        val oldGroupLimit = if (oldGroup != null) prefs.getLong("limit_$oldGroup", 0L) else 0L
        val others = (shareWith - id).intersect(all)

        val newGroup: String? = if (others.isEmpty()) null
        else oldGroup ?: others.firstNotNullOfOrNull { groups[it] } ?: "g_$id"

        groups[id] = newGroup
        if (newGroup != null) others.forEach { groups[it] = newGroup }

        if (oldGroup != null) {
            val remaining = all.filter { groups[it] == oldGroup }
            val release = when {
                newGroup == oldGroup -> remaining.filter { it != id && it !in others }
                remaining.size < 2 -> remaining
                else -> emptyList()
            }
            release.forEach {
                groups[it] = null
                limits["limit_$it"] = oldGroupLimit
            }
        }
        limits["limit_" + (newGroup ?: id)] = limitMinor

        val editor = prefs.edit()
        groups.forEach { (card, group) ->
            if (group == null) editor.remove("group_$card") else editor.putString("group_$card", group)
        }
        limits.forEach { (key, value) -> editor.putLong(key, value) }
        editor.putInt("due_$id", dueDay)
        editor.apply()
    }
}
