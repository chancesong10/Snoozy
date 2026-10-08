package com.snoozy.app

import android.app.Activity
import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Snoozes that start on their own at set times, like every night at bedtime.
class SchedulesActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    // A schedule starting or ending changes what its row can do.
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedules)
        findViewById<TextView>(R.id.pageTitle).text = "Schedules"
        findViewById<ImageView>(R.id.pageIcon).setImageResource(R.drawable.ic_schedule)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.schedulesIntro).text =
            "Snoozy starts snoozing on its own at these times and keeps Reels & Shorts " +
                "locked until the time is up. A running schedule can't be turned off or removed."

        findViewById<View>(R.id.addSchedule).setOnClickListener { showAddDialog() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val schedules = Schedules.get(this)
        val list = findViewById<LinearLayout>(R.id.schedulesList)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (s in schedules) {
            val row = inflater.inflate(R.layout.item_schedule, list, false)
            val running = Schedules.isRunning(s)
            row.findViewById<TextView>(R.id.scheduleTime).text =
                "${Schedules.timeLabel(this, s.start)} – ${Schedules.timeLabel(this, s.end)}"
            row.findViewById<TextView>(R.id.scheduleDetail).text =
                if (running) "Snoozing now, locked until ${Schedules.timeLabel(this, s.end)}"
                else "${Schedules.daysLabel(s.days)} · ${SnoozeOptions.label(s.minutes)}"

            val switch = row.findViewById<Switch>(R.id.scheduleSwitch)
            switch.isChecked = s.enabled
            switch.isEnabled = !running
            switch.setOnCheckedChangeListener { _, on ->
                if (on && Schedules.window(s) != null) {
                    confirmStartNow(s) { Schedules.setEnabled(this, s.id, true) }
                } else {
                    Schedules.setEnabled(this, s.id, on)
                    render()
                }
            }

            val remove = row.findViewById<View>(R.id.scheduleRemove)
            remove.isEnabled = !running
            remove.alpha = if (running) 0.35f else 1f
            remove.setOnClickListener {
                Schedules.remove(this, s.id)
                render()
            }
            list.addView(row)
        }
        findViewById<View>(R.id.schedulesEmpty).visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE

        val add = findViewById<TextView>(R.id.addSchedule)
        val full = schedules.size >= Schedules.MAX_SCHEDULES
        add.isEnabled = !full
        add.text = if (full) "Full: remove one to add another" else "+  Add schedule"
        add.setTextColor(getColor(if (full) R.color.ink_soft else R.color.card))
    }

    // Start time from the system clock picker, length from the same wheels
    // as Snooze options, and the days it starts on. Defaults to bedtime.
    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_schedule, null)
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        var start = DEFAULT_START
        var days = Schedules.EVERY_DAY

        val startLabel = view.findViewById<TextView>(R.id.startLabel)
        val lengthLabel = view.findViewById<TextView>(R.id.lengthLabel)
        val hours = view.findViewById<NumberPicker>(R.id.pickHours)
        val minutes = view.findViewById<NumberPicker>(R.id.pickMinutes)
        val addYes = view.findViewById<TextView>(R.id.addYes)
        val error = view.findViewById<TextView>(R.id.addError)

        hours.minValue = 0
        hours.maxValue = SnoozeOptions.MAX_MINUTES / 60
        hours.value = DEFAULT_HOURS
        val minuteSteps = (0 until 60 step SnoozeOptions.MINUTE_STEP).toList()
        minutes.minValue = 0
        minutes.maxValue = minuteSteps.size - 1
        minutes.displayedValues = minuteSteps.map { "%02d".format(it) }.toTypedArray()
        minutes.value = 0

        fun total() = hours.value * 60 + minuteSteps[minutes.value]
        fun update() {
            if (hours.value == hours.maxValue) minutes.value = 0
            minutes.isEnabled = hours.value < hours.maxValue
            startLabel.text = Schedules.timeLabel(this, start)
            lengthLabel.text =
                if (total() == 0) "Snooze for"
                else "Snooze for · ends ${Schedules.timeLabel(this, (start + total()) % (24 * 60))}"
            addYes.text = if (total() == 0) "Pick a length" else "Save schedule"
            error.visibility = View.GONE
        }
        hours.setOnValueChangedListener { _, _, _ -> update() }
        minutes.setOnValueChangedListener { _, _, _ -> update() }

        view.findViewById<View>(R.id.pickStart).setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                start = h * 60 + m
                update()
            }, start / 60, start % 60, DateFormat.is24HourFormat(this)).show()
        }

        // One chip per day, all on to start with.
        val dayRow = view.findViewById<LinearLayout>(R.id.pickDays)
        val narrow = SimpleDateFormat("EEEEE", Locale.getDefault())
        val cal = Calendar.getInstance()
        for (dow in Schedules.weekOrder()) {
            val chip = LayoutInflater.from(this).inflate(R.layout.item_chip, dayRow, false) as TextView
            cal.set(Calendar.DAY_OF_WEEK, dow)
            chip.text = narrow.format(cal.time)
            chip.textSize = 13f
            chip.isSelected = true
            chip.setOnClickListener {
                days = days xor Schedules.dayBit(dow)
                chip.isSelected = (days and Schedules.dayBit(dow)) != 0
                update()
            }
            dayRow.addView(chip)
        }
        update()

        addYes.setOnClickListener {
            val problem = Schedules.problem(this, total(), days)
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val length = total()
            val save = {
                Schedules.add(this, start, length, days)
                dialog.dismiss()
                render()
            }
            val draft = Schedules.Schedule(0L, start, length, days, enabled = true)
            if (Schedules.window(draft) != null) confirmStartNow(draft, save) else save()
        }
        view.findViewById<View>(R.id.addNo).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // A schedule that covers right now locks straight away, and a lock can't
    // be undone, so double-check first.
    private fun confirmStartNow(s: Schedules.Schedule, onYes: () -> Unit) {
        val until = Schedules.window(s)?.end ?: return onYes()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        view.findViewById<TextView>(R.id.confirmTitle).text = "Start snoozing now?"
        view.findViewById<TextView>(R.id.confirmMessage).text =
            "This schedule covers right now, so Reels & Shorts will stay blocked until " +
                "${Schedules.whenLabel(this, until)}. You won't be able to turn it off before then, not even from this app."

        view.findViewById<View>(R.id.confirmYes).setOnClickListener {
            onYes()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.confirmNo).setOnClickListener { dialog.dismiss() }
        // Also puts a switch back if the answer was no.
        dialog.setOnDismissListener { render() }
        dialog.show()
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val DEFAULT_START = 23 * 60
        private const val DEFAULT_HOURS = 8
    }
}
