#!/usr/bin/env kotlin

@file:DependsOn("org.seleniumhq.selenium:selenium-java:4.25.0")

import org.openqa.selenium.By
import org.openqa.selenium.WebElement
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

fun help(msg: String? = null): Nothing {
    if (msg != null) println(msg)
    println("Usage: ")
    println("    petersonio --domain <domain> --username <username> --workTime <start>-<end> --breakTime <start>-<end>")
    exitProcess(-1)
}

val argMap = args.toList().chunked(2).associate { it[0] to it.getOrElse(1) { help() } }
fun getArg(name: String) = argMap[name] ?: help("argument '$name' not defined")
fun getArgTimeRange(name: String) = getArg(name).split("-").let { range ->
    if (range.size != 2 || !range.all { Regex("\\d{1,2}:\\d{2}").matches(it) })
        help("invalid time range format for '$name', expected format:  hh:mm-hh:mm")
    range[0] to range[1]
}

val domain = getArg("--domain")
val username = getArg("--username")
val password = System.getenv("PETERSONIO_PASSWORD") ?: help("environment variable 'PETERSONIO_PASSWORD' not defined")
val workTime = getArgTimeRange("--workTime")
val breakTime = getArgTimeRange("--breakTime")

val chromeOptions: ChromeOptions = ChromeOptions().addArguments(
    "--headless",
    "--no-sandbox",
    "--disable-dev-shm-usage",
    "--disable-gpu",
    "--window-size=1920x1080",
)

fun ChromeDriver.waitForReady(
    timeout: Duration = 60.seconds,
    block: ChromeDriver.() -> Boolean = { executeScript("return document.readyState") == "complete" }
) {
    for (i in 1..10) {
        if (block()) return
        Thread.sleep(timeout.inWholeMilliseconds / 10)
    }
    throw Exception("Timed out")
}

fun ChromeDriver.waitFor(locator: By): WebElement = waitForAll(locator).single()
fun ChromeDriver.waitForDisappear(locator: By) = waitForReady { findElements(locator).isEmpty() }
fun ChromeDriver.safeClick(locator: By) = safeClick(waitFor(locator))
fun ChromeDriver.safeClick(element: WebElement) {
    WebDriverWait(this, 30.seconds.toJavaDuration()).until(ExpectedConditions.elementToBeClickable(element))
    element.click()
}

fun ChromeDriver.waitForAll(
    locator: By,
): List<WebElement> {
    var result = emptyList<WebElement>()
    waitForReady { result = findElements(locator).filterNotNull(); result.isNotEmpty() }
    return result
}

fun chromeDriver(block: ChromeDriver.() -> Unit) {
    val driver = ChromeDriver(chromeOptions)
    try {
        driver.block()
    } catch (e: Exception) {
        System.err.println(e)
        e.printStackTrace(System.err)
    }
    driver.quit()
}

fun ChromeDriver.bookTimesForCurrentMonth(): Result<Int> = runCatching {
    var count = 0
    while (true) {
        Thread.sleep(1000)
        val day = waitForAll(By.cssSelector("button[data-test-id=\"day-cell-action-button\"]"))
            .firstOrNull { it.text.contains("Keine Zeit erfasst") } ?: break
        safeClick(day)
        waitFor(By.cssSelector("section[data-test-id=\"work-entry\"] input[data-test-id=\"timerange-start\"]"))
            .sendKeys(workTime.first)
        waitFor(By.cssSelector("section[data-test-id=\"work-entry\"] input[data-test-id=\"timerange-end\"]"))
            .sendKeys(workTime.second)
        waitFor(By.cssSelector("section[data-test-id=\"break-entry\"] input[data-test-id=\"timerange-start\"]"))
            .sendKeys(breakTime.first)
        waitFor(By.cssSelector("section[data-test-id=\"break-entry\"] input[data-test-id=\"timerange-end\"]"))
            .sendKeys(breakTime.second)
        val saveButtonLocator = By.cssSelector("button[data-test-id=\"day-entry-save\"]")
        waitFor(saveButtonLocator).click()
        waitForDisappear(saveButtonLocator)
        waitForReady()
        Thread.sleep(1000)
        println("work time booked")
        count++
    }
    count
}

chromeDriver {
    get("https://$domain")
    waitFor(By.id("email")).sendKeys(username)
    waitFor(By.id("password")).sendKeys(password)
    safeClick(By.cssSelector("button[type=\"submit\"]"))
    safeClick(By.cssSelector("a[data-test-id=\"navsidebar-time_tracking\"]"))
    bookTimesForCurrentMonth().onFailure {
        System.err.println("Booking failed in current month: ${it.message}")
        it.printStackTrace(System.err)
        return@chromeDriver
    }.onSuccess {
        println("time booked for $it day${if (it == 1) "" else "s"} in current month")
    }
    safeClick(By.cssSelector("button[data-test-id=\"week-navigation-back\"]"))
    bookTimesForCurrentMonth().onFailure {
        System.err.println("Booking failed: ${it.message}")
        it.printStackTrace(System.err)
    }.onFailure {
        System.err.println("Booking failed in previous month: ${it.message}")
        it.printStackTrace(System.err)
        return@chromeDriver
    }.onSuccess {
        println("time booked for $it day${if (it == 1) "" else "s"} in previous month")
    }
}
