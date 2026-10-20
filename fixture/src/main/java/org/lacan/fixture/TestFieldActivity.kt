package org.lacan.fixture

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.*

/** Deliberately separate package: exercises insertion into another app, never shipped in Lacan APK. */
class TestFieldActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 100, 32, 32) }
        box.addView(TextView(this).apply { text = "Lacan · тест чужого поля"; textSize = 24f })
        val input = EditText(this).apply {
            id = 1001; contentDescription = "Поле для проверки вставки"
            inputType = if (intent.getBooleanExtra("password", false)) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText("начало конец"); setSelection(7); minLines = 3
        }
        box.addView(input)
        box.addView(EditText(this).apply { id = 1002; hint = "Второе поле"; contentDescription = "Второе поле" })
        box.addView(TextView(this).apply { text = "Здесь нет кнопки отправки. Это локальная тестовая форма." })
        setContentView(box); input.requestFocus()
        input.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 300)
    }
}
