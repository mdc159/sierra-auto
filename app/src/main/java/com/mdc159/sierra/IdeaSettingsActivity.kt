package com.mdc159.sierra

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mdc159.sierra.agent.ConfigStore
import com.mdc159.sierra.agent.tools.FileIdeaTool
import com.mdc159.sierra.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone-side settings for the idea loop (docs/IDEA-LOOP.md): the fine-grained GitHub
 * token the Talk agent's `file_idea` tool uses to open issues labelled `idea`.
 * The token is stored encrypted next to the agent key and never leaves the phone except
 * in the Authorization header to api.github.com.
 */
class IdeaSettingsActivity : ComponentActivity() {

    private lateinit var token: EditText
    private lateinit var saved: TextView
    private lateinit var testButton: Button
    private lateinit var testResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun paragraph(text: String, size: Float = 14f) = form.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        })

        paragraph("Idea loop", 24f)
        paragraph(
            "Say \"file an idea\" on the truck and Sierra opens a GitHub issue labelled \"idea\" on " +
                FileIdeaTool.REPO + ". A cloud agent then turns it into a pull request.",
        )
        paragraph(
            "Make a fine-grained personal access token at github.com → Settings → Developer settings → " +
                "Fine-grained tokens. Repository access: only sierra-auto. Permissions: Issues, read and write. " +
                "Nothing else.",
        )

        form.addView(TextView(this).apply { text = "GitHub token" })
        token = EditText(this).apply {
            hint = "github_pat_…"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(token, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        saved = TextView(this)
        form.addView(saved)

        form.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        })
        testButton = Button(this).apply {
            text = "Test token"
            setOnClickListener { testToken() }
        }
        form.addView(testButton)
        testResult = TextView(this)
        form.addView(testResult)
        form.addView(Button(this).apply {
            text = "Forget token"
            setOnClickListener {
                token.setText("")
                ConfigStore.saveIdeaToken(this@IdeaSettingsActivity, "")
                refreshSaved()
                Toast.makeText(this@IdeaSettingsActivity, "Token removed.", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(form) })
        token.setText(ConfigStore.loadIdeaToken(this))
        refreshSaved()
    }

    private fun save() {
        ConfigStore.saveIdeaToken(this, token.text.toString())
        refreshSaved()
        Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSaved() {
        val stored = ConfigStore.loadIdeaToken(this)
        saved.text = if (stored.isBlank()) "No token saved. The file_idea tool is off." else "Token saved (${describe(stored)})."
    }

    /** Saves first, then asks GitHub whether the token can see the repo. Creates nothing. */
    private fun testToken() {
        ConfigStore.saveIdeaToken(this, token.text.toString())
        refreshSaved()
        testButton.isEnabled = false
        testResult.text = "Testing…"
        lifecycleScope.launch {
            val tool = FileIdeaTool(token = { ConfigStore.loadIdeaToken(this@IdeaSettingsActivity) })
            testResult.text = try {
                withContext(Dispatchers.IO) { tool.checkToken() }
            } catch (e: Exception) {
                "Failed: ${e.message ?: Http.describe(e)}"
            }
            testButton.isEnabled = true
        }
    }

    companion object {
        /** Shows only the tail of a token so the screen never displays the secret. */
        fun describe(token: String): String =
            if (token.length <= 4) "set" else "ends in …${token.takeLast(4)}"
    }
}
