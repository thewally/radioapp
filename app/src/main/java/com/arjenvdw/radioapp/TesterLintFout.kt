package com.arjenvdw.radioapp

import android.content.Context
import android.widget.Toast

// Tijdelijk testbestand kaart 17: bewuste lint-error (WrongConstant)
fun testerLintFout(context: Context) {
    Toast.makeText(context, "x", 5).show()
}
