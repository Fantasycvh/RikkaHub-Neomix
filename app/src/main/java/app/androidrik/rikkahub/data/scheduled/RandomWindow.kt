package app.androidrik.rikkahub.data.scheduled

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.Random

/** Version 1 uses java.util.Random's specified 48-bit LCG and a partial Fisher-Yates shuffle. */
@Serializable
data class RandomWindow(
    val startMinute: Int,
    val endMinute: Int,
    val slotsPerDay: Int,
    val seed: Long,
    val algorithmVersion: Int = 1,
) {
    fun validate() {
        require(algorithmVersion == 1) { "Unsupported random schedule version" }
        require(startMinute in 0..1439 && endMinute in 1..1440 && startMinute < endMinute) {
            "Random window must end after its start on the same day"
        }
        require(slotsPerDay in 1..minOf(20, endMinute - startMinute)) { "Too many slots for the random window" }
    }

    fun minutesOn(date: LocalDate): List<Int> {
        validate()
        val random = Random(seed xor (date.toEpochDay() * -7046029254386353131L))
        val pool = (startMinute until endMinute).toMutableList()
        repeat(slotsPerDay) { index ->
            val selected = index + random.nextInt(pool.size - index)
            val value = pool[index]
            pool[index] = pool[selected]
            pool[selected] = value
        }
        return pool.take(slotsPerDay).sorted()
    }
}
