// Savers for state that rememberSaveable's default saver can't carry, so a
// half-finished flow survives a rotation or a low-memory kill. The Bundle-backed
// registry only accepts primitives, so each of these flattens to one.

package com.christianmolinari.scorecard.ui.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.time.LocalDate
import java.time.LocalTime

val StringListStateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

val IntListStateSaver = listSaver<List<Int>, Int>(save = { it }, restore = { it })

val LocalDateStateSaver: Saver<LocalDate, Long> =
    Saver(save = { it.toEpochDay() }, restore = { LocalDate.ofEpochDay(it) })

val LocalTimeStateSaver: Saver<LocalTime, Int> =
    Saver(save = { it.toSecondOfDay() }, restore = { LocalTime.ofSecondOfDay(it.toLong()) })
