package com.markopacak.divelens.ui.help

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackMessageTest {
    @Test fun bodyHoldsVersionAndroidAndPhoneOnly() {
        assertEquals(
            "\n\n---\nDiveLens 1.0\nAndroid 16 (API 36)\nPhone: Xiaomi 2406APNFAG\n",
            FeedbackMessage.body("1.0", "16", 36, "Xiaomi", "2406APNFAG"),
        )
    }

    @Test fun manufacturerIsNotRepeated() {
        assertEquals(
            "\n\n---\nDiveLens 1.0\nAndroid 15 (API 35)\nPhone: Google Pixel 9\n",
            FeedbackMessage.body("1.0", "15", 35, "Google", "Google Pixel 9"),
        )
    }

    @Test fun subjectAndAddresses() {
        assertEquals("DiveLens feedback", FeedbackMessage.SUBJECT)
        assertEquals("dev.markopacak@gmail.com", Links.FEEDBACK_EMAIL)
        assertEquals("https://github.com/markopacak/divelens/issues", Links.ISSUES)
    }
}
