package com.kazeracer.livenote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Not a test: puts a sample note in place for the CI screenshots. */
@RunWith(AndroidJUnit4::class)
class DemoNote {
    @Test fun writeDemoNote() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Scheduler.cancel(ctx)
        val body = listOf(
            "> ## Spesa", "  [] Latte", "  [x] Pane", "  [] Uova",
            "# Oggi", "- Chiamare **Marco**", "- Palestra alle 18", "1. Mail al commercialista", "2. `fattura.pdf`",
            "\" Meno, ma meglio.", "---", "Riga 1", "Riga 2", "Riga 3", "Riga 4",
        ).joinToString("\n")
        val store = NoteStore(ctx)
        store.save("Promemoria", body, emptySet())
        store.pinned = true
        NoteNotifier.refresh(ctx)
    }
}
