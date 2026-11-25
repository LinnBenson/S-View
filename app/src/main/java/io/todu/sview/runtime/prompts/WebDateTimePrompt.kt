package io.todu.sview.runtime.prompts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.todu.sview.ui.components.AppTextField
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields

/** 日期字段使用本地日历语义，不经过时区转换；约束校验独立于界面，便于覆盖边界。 */
internal object WebDateValues {
    private val weekEpoch = LocalDate.of(1969, 12, 29)
    private fun weekDate(value: String): LocalDate {
        require(value.matches(Regex("[0-9]{4,}-W[0-9]{2}")))
        val year = value.substringBefore('-').toInt()
        val week = value.substringAfter('W').toInt()
        val date = LocalDate.of(year, 1, 4).with(WeekFields.ISO.dayOfWeek(), 1)
            .plusWeeks((week - 1).toLong())
        require(week in 1..53 && date.get(IsoFields.WEEK_BASED_YEAR) == year)
        return date
    }

    /** 将各输入类型映射为规范步进单位：日、月、周或秒。 */
    fun scalar(type: Int, value: String): BigDecimal = when (type) {
        DateTimePrompt.Type.DATE -> BigDecimal.valueOf(LocalDate.parse(value).toEpochDay())
        DateTimePrompt.Type.MONTH -> YearMonth.parse(value).let { BigDecimal.valueOf((it.year - 1970L) * 12 + it.monthValue - 1) }
        DateTimePrompt.Type.WEEK -> BigDecimal.valueOf((weekDate(value).toEpochDay() - weekEpoch.toEpochDay()) / 7)
        DateTimePrompt.Type.TIME -> LocalTime.parse(value).let { BigDecimal.valueOf(it.toNanoOfDay(), 9) }
        DateTimePrompt.Type.DATETIME_LOCAL -> LocalDateTime.parse(value).let {
            BigDecimal.valueOf(it.toEpochSecond(ZoneOffset.UTC)) + BigDecimal.valueOf(it.nano.toLong(), 9)
        }
        else -> throw IllegalArgumentException("不支持的日期类型")
    }

    fun error(type: Int, value: String, minimum: String?, maximum: String?, step: String?, initial: String?): String? {
        if (value.isEmpty()) return null
        val number = runCatching { scalar(type, value) }.getOrNull() ?: return "请输入有效日期或时间"
        val min = minimum?.let { runCatching { scalar(type, it) }.getOrNull() }
        val max = maximum?.let { runCatching { scalar(type, it) }.getOrNull() }
        val outside = if (type == DateTimePrompt.Type.TIME && min != null && max != null && min > max) {
            number < min && number > max
        } else (min != null && number < min) || (max != null && number > max)
        if (outside) return "超出允许范围"
        if (step == "any") return null
        val default = if (type == DateTimePrompt.Type.TIME || type == DateTimePrompt.Type.DATETIME_LOCAL) "60" else "1"
        val interval = step?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal(default)
        val base = min ?: initial?.let { runCatching { scalar(type, it) }.getOrNull() } ?: BigDecimal.ZERO
        return if ((number - base).remainder(interval).compareTo(BigDecimal.ZERO) == 0) null else "请选择符合步长 $interval 的值"
    }

    data class TimePrecision(val seconds: Boolean, val fraction: Boolean)

    /** min 也是步进基准，必须能编辑其中的秒和小数秒，不能只看默认值。 */
    fun precision(type: Int, initial: String, minimum: String?, step: String?): TimePrecision {
        fun time(value: String?): LocalTime? = runCatching {
            if (type == DateTimePrompt.Type.TIME) LocalTime.parse(value)
            else LocalDateTime.parse(value).toLocalTime()
        }.getOrNull()
        val times = listOfNotNull(time(initial), time(minimum))
        val interval = step?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
        val fraction = step == "any" || times.any { it.nano != 0 } ||
            (interval != null && interval.stripTrailingZeros().scale() > 0)
        val seconds = fraction || times.any { it.second != 0 } ||
            (interval != null && interval.remainder(BigDecimal(60)).compareTo(BigDecimal.ZERO) != 0)
        return TimePrecision(seconds, fraction)
    }

    fun initial(type: Int, value: String?, minimum: String?): String {
        for (candidate in listOf(value, minimum)) {
            if (!candidate.isNullOrBlank() && runCatching { scalar(type, candidate) }.isSuccess) return candidate
        }
        val date = LocalDate.now()
        return when (type) {
            DateTimePrompt.Type.DATE -> date.toString()
            DateTimePrompt.Type.MONTH -> YearMonth.from(date).toString()
            DateTimePrompt.Type.WEEK -> "%04d-W%02d".format(java.util.Locale.ROOT, date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            DateTimePrompt.Type.TIME -> "00:00"
            else -> "${date}T00:00"
        }
    }
}

/** 日期和时间分段展示滚轮，避免小圆屏同时挤入六个字段。 */
@Composable
internal fun DateTimeContent(request: WebPromptRequest, prompt: DateTimePrompt) {
    val watch = io.todu.sview.wear.WatchDevice.isWatch(LocalContext.current)
    val type = prompt.type
    val initial = remember(prompt) { WebDateValues.initial(type, prompt.defaultValue, prompt.minValue) }
    val today = LocalDate.now()
    val dateParts = initial.substringBefore('T').replace("W", "").split('-')
    val time = runCatching { LocalTime.parse(if (type == DateTimePrompt.Type.TIME) initial else initial.substringAfter('T', "00:00")) }
        .getOrDefault(LocalTime.MIDNIGHT)
    var year by remember(prompt) { mutableIntStateOf(dateParts.getOrNull(0)?.toIntOrNull() ?: today.year) }
    var month by remember(prompt) { mutableIntStateOf(dateParts.getOrNull(1)?.toIntOrNull() ?: 1) }
    var day by remember(prompt) { mutableIntStateOf(dateParts.getOrNull(2)?.toIntOrNull() ?: 1) }
    var hour by remember(prompt) { mutableIntStateOf(time.hour) }
    var minute by remember(prompt) { mutableIntStateOf(time.minute) }
    var second by remember(prompt) { mutableIntStateOf(time.second) }
    var fraction by remember(prompt) { mutableStateOf(time.nano.toString().padStart(9, '0').trimEnd('0')) }
    var showTime by remember(prompt) { mutableStateOf(type == DateTimePrompt.Type.TIME) }
    var moving by remember(prompt) { mutableStateOf(emptySet<String>()) }
    fun scrolling(field: String, active: Boolean) { moving = if (active) moving + field else moving - field }
    val precision = remember(prompt) { WebDateValues.precision(type, initial, prompt.minValue, prompt.stepValue) }
    val precise = precision.fraction
    val seconds = precision.seconds
    val week = type == DateTimePrompt.Type.WEEK
    val lastMonth = if (week) LocalDate.of(year, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) else 12
    val validMonth = month.coerceIn(1, lastMonth)
    val lastDay = if (week) 31 else YearMonth.of(year, validMonth).lengthOfMonth()
    val validDay = day.coerceIn(1, lastDay)
    val date = "%04d-%02d-%02d".format(java.util.Locale.ROOT, year, validMonth, validDay)
    val clock = "%02d:%02d:%02d".format(java.util.Locale.ROOT, hour, minute, second) + if (fraction.isEmpty()) "" else ".$fraction"
    val value = when (type) {
        DateTimePrompt.Type.DATE -> date
        DateTimePrompt.Type.MONTH -> "%04d-%02d".format(java.util.Locale.ROOT, year, validMonth)
        DateTimePrompt.Type.WEEK -> "%04d-W%02d".format(java.util.Locale.ROOT, year, validMonth)
        DateTimePrompt.Type.TIME -> clock
        else -> "${date}T$clock"
    }
    val error = WebDateValues.error(type, value, prompt.minValue, prompt.maxValue, prompt.stepValue, prompt.defaultValue)
    PromptPanel(if (showTime) "选择时间" else "选择日期", request::cancel,
        { request.complete { prompt.confirm(value) } }, enabled = error == null && moving.isEmpty()) {
        if (type == DateTimePrompt.Type.DATETIME_LOCAL) item {
            PromptTabs(listOf("日期", "时间"), if (showTime) 1 else 0, compact = true, enabled = moving.isEmpty()) { showTime = it == 1 }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(if (watch) .9f else 1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showTime) {
                        NumberWheel("时", hour, 0..23, { hour = it }, Modifier.weight(1f)) { scrolling("hour", it) }
                        NumberWheel("分", minute, 0..59, { minute = it }, Modifier.weight(1f)) { scrolling("minute", it) }
                        if (seconds) NumberWheel("秒", second, 0..59, { second = it }, Modifier.weight(1f)) { scrolling("second", it) }
                    } else {
                        NumberWheel("年", year, 1..9999, { year = it }, Modifier.weight(1.25f)) { scrolling("year", it) }
                        NumberWheel(if (week) "周" else "月", validMonth, 1..lastMonth, { month = it }, Modifier.weight(1f)) { scrolling("month", it) }
                        if (type == DateTimePrompt.Type.DATE || type == DateTimePrompt.Type.DATETIME_LOCAL) {
                            NumberWheel("日", validDay, 1..lastDay, { day = it }, Modifier.weight(1f)) { scrolling("day", it) }
                        }
                    }
                }
            }
        }
        if (showTime && precise) item {
            AppTextField(fraction, { next -> if (next.length <= 9 && next.all { it in '0'..'9' }) fraction = next },
                label = { Text("小数秒（0.…）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                containerColor = MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurface,
                outlineColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        if (error != null) item {
            Text(error, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            if (error == "超出允许范围") Text(listOfNotNull(prompt.minValue, prompt.maxValue).joinToString(" ～ "))
        }
        item { TextButton(onClick = { request.complete { prompt.confirm("") } }) { Text("清空") } }
    }
}
