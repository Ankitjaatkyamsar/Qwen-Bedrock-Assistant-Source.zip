package com.ankit.qwenbedrock

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

class QwenToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = QwenAssistantPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class QwenAssistantPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val baseUrl = JBTextField("https://bedrock-mantle.ap-south-1.api.aws/v1")
    private val model = JBTextField("qwen.qwen3-coder-480b-a35b-instruct")
    private val apiKey = JBPasswordField()
    private val includeEditor = JCheckBox("Current file / selected code include karo", true)
    private val prompt = JBTextArea(6, 40)
    private val output = JBTextArea(18, 40)
    private val send = JButton("Send")
    private val insert = JButton("Insert response at cursor")
    private val clear = JButton("Clear")

    init {
        output.isEditable = false
        output.lineWrap = true
        output.wrapStyleWord = true
        prompt.lineWrap = true
        prompt.wrapStyleWord = true

        val saved = PasswordSafe.instance.getPassword(credentialAttributes())
        if (!saved.isNullOrBlank()) apiKey.text = saved

        val settings = JPanel()
        settings.layout = javax.swing.BoxLayout(settings, javax.swing.BoxLayout.Y_AXIS)
        settings.add(labeled("Base URL", baseUrl))
        settings.add(labeled("Model", model))
        settings.add(labeled("Bedrock API Key", apiKey))
        settings.add(includeEditor)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT))
        buttons.add(send)
        buttons.add(insert)
        buttons.add(clear)

        val top = JPanel(BorderLayout())
        top.add(settings, BorderLayout.NORTH)
        top.add(JLabel("Prompt"), BorderLayout.CENTER)
        top.add(JBScrollPane(prompt), BorderLayout.SOUTH)

        val bottom = JPanel(BorderLayout())
        bottom.add(buttons, BorderLayout.NORTH)
        bottom.add(JBScrollPane(output), BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom)
        split.resizeWeight = 0.35
        add(split, BorderLayout.CENTER)

        send.addActionListener { sendRequest() }
        insert.addActionListener { insertResponse() }
        clear.addActionListener {
            prompt.text = ""
            output.text = ""
        }
    }

    private fun labeled(text: String, field: javax.swing.JComponent): JPanel {
        val row = JPanel(BorderLayout(8, 4))
        row.add(JLabel(text), BorderLayout.WEST)
        row.add(field, BorderLayout.CENTER)
        return row
    }

    private fun sendRequest() {
        // Swing/editor values EDT par hi read karo. Background thread se UI access unstable ho sakta hai.
        val key = String(apiKey.password).trim()
        val userPrompt = prompt.text.trim()
        val endpointBase = baseUrl.text.trim()
        val modelId = model.text.trim()
        val context = if (includeEditor.isSelected) editorContext() else ""

        if (key.isBlank()) {
            output.text = "Bedrock API key dalo."
            return
        }
        if (userPrompt.isBlank()) {
            output.text = "Prompt likho."
            return
        }
        if (endpointBase.isBlank()) {
            output.text = "Base URL blank nahi ho sakta."
            return
        }
        if (modelId.isBlank()) {
            output.text = "Model ID blank nahi ho sakta."
            return
        }

        PasswordSafe.instance.set(credentialAttributes(), Credentials("bedrock", key))
        send.isEnabled = false
        output.text = "Qwen se response aa raha hai..."

        val finalPrompt = if (context.isBlank()) {
            userPrompt
        } else {
            "$userPrompt\n\nAndroid Studio editor context:\n```\n$context\n```"
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val answer = callBedrock(key, endpointBase, modelId, finalPrompt)
                SwingUtilities.invokeLater {
                    output.text = answer
                    output.caretPosition = 0
                    send.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    output.text = "Error: ${e.message ?: e.javaClass.simpleName}"
                    send.isEnabled = true
                }
            }
        }
    }

    private fun editorContext(): String {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return ""
        val selected = editor.selectionModel.selectedText
        return (selected ?: editor.document.text).take(MAX_CONTEXT_CHARS)
    }

    private fun callBedrock(
        key: String,
        endpointBase: String,
        modelId: String,
        message: String
    ): String {
        val endpoint = endpointBase.trimEnd('/') + "/chat/completions"
        val body = """{"model":"${jsonEscape(modelId)}","messages":[{"role":"user","content":"${jsonEscape(message)}"}],"max_tokens":4096}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofMinutes(3))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(2000)}")
        }

        return extractContent(response.body()).ifBlank {
            throw IllegalStateException("Bedrock ne empty response diya.")
        }
    }

    private fun insertResponse() {
        val text = output.text
        if (text.isBlank() || text.startsWith("Error:") || text == "Qwen se response aa raha hai...") return

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            output.text = "Pehle editor me koi file kholo.\n\n$text"
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                editor.document.replaceString(selection.selectionStart, selection.selectionEnd, text)
            } else {
                editor.document.insertString(editor.caretModel.offset, text)
            }
        }
    }

    private fun credentialAttributes() = CredentialAttributes("QwenBedrockAssistant.ApiKey")

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private fun extractContent(json: String): String {
        val marker = "\"content\""
        var index = json.indexOf(marker)
        if (index < 0) return json

        index = json.indexOf(':', index + marker.length)
        if (index < 0) return json
        index++

        while (index < json.length && json[index].isWhitespace()) index++
        if (index >= json.length || json[index] != '"') return json
        index++

        val result = StringBuilder()
        while (index < json.length) {
            val c = json[index++]
            if (c == '"') break
            if (c != '\\') {
                result.append(c)
                continue
            }
            if (index >= json.length) break

            when (val escaped = json[index++]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'u' -> {
                    if (index + 4 <= json.length) {
                        val hex = json.substring(index, index + 4)
                        result.append(hex.toIntOrNull(16)?.toChar() ?: "\\u$hex")
                        index += 4
                    }
                }
                else -> result.append(escaped)
            }
        }
        return result.toString()
    }

    companion object {
        private const val MAX_CONTEXT_CHARS = 30_000
    }
}
