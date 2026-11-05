package com.mdc159.sierra.car

import android.text.format.DateUtils
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.mdc159.sierra.agent.Conversation
import com.mdc159.sierra.agent.Exchange
import com.mdc159.sierra.agent.History

/** Recent questions, newest first, capped by the host's list limit. */
class HistoryScreen(
    carContext: CarContext,
    private val session: SierraSession,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val entries = History.list(carContext).take(CarLimits.listLimit(carContext))
        val items = ItemList.Builder().setNoItemsMessage("No conversations yet. Tap Talk to start.")
        entries.forEach { e ->
            items.addItem(
                Row.Builder()
                    .setTitle(Conversation.truncateForScreen(e.question, 60))
                    .addText(
                        DateUtils.getRelativeTimeSpanString(e.timeMillis).toString() + " · " +
                            Conversation.truncateForScreen(e.answer, 60)
                    )
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(HistoryDetailScreen(carContext, session, e)) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("History")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/** One past exchange, with the option to hear the answer again. */
class HistoryDetailScreen(
    carContext: CarContext,
    private val session: SierraSession,
    private val exchange: Exchange,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val maxRows = CarLimits.listLimit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("You: " + Conversation.truncateForScreen(exchange.question, 80)).build())
        ReplyLayout.chunks(exchange.answer, maxRows - 1).forEach { pane.addRow(Row.Builder().setTitle(it).build()) }
        pane.addAction(
            Action.Builder()
                .setTitle("Hear it")
                .setFlags(Action.FLAG_PRIMARY)
                .setOnClickListener { session.speaker.speak(Conversation.forSpeech(exchange.answer)) }
                .build()
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle("History")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
