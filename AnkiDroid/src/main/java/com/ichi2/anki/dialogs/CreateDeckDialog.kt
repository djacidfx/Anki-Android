/****************************************************************************************
 * Copyright (c) 2021 Akshay Jadhav <jadhavakshay0701@gmail.com>                        *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki.dialogs

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.annotations.NeedsTest
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Decks
import com.ichi2.anki.showThemedToast
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.utils.getInputField
import com.ichi2.utils.getInputTextLayout
import com.ichi2.utils.input
import com.ichi2.utils.negativeButton
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show
import com.ichi2.utils.title
import net.ankiweb.rsdroid.exceptions.BackendDeckIsFilteredException
import timber.log.Timber

/**
 * A dialog which manages the creation of decks, subdecks and filtered decks.
 *
 * Also used for deck renames: [DeckDialogType.RENAME_DECK]
 *
 * required property: [onNewDeckCreated]. Called on successful creation of a deck
 */
@NeedsTest("Ensure a toast is shown on a successful action")
class CreateDeckDialog(
    private val context: Context,
    private val title: Int,
    private val deckDialogType: DeckDialogType,
    private val parentId: DeckId?,
) {
    private var previousDeckName: String? = null
    lateinit var onNewDeckCreated: ((DeckId) -> Unit)
    private var initialDeckName = ""
    private var shownDialog: AlertDialog? = null

    enum class DeckDialogType {
        FILTERED_DECK,
        DECK,
        SUB_DECK,
        RENAME_DECK,
    }

    private val getColUnsafe
        get() = CollectionManager.getColUnsafe()

    suspend fun showFilteredDeckDialog() {
        Timber.i("CreateDeckDialog::showFilteredDeckDialog")
        initialDeckName =
            withCol {
                sched.getOrCreateFilteredDeck(did = 0).name
            }
        showDialog()
    }

    /** Used for rename  */
    var deckName: String
        get() = shownDialog!!.getInputField().text.toString()
        set(deckName) {
            previousDeckName = deckName
            initialDeckName = deckName
        }

    fun showDialog(): AlertDialog {
        val dialog =
            AlertDialog
                .Builder(context)
                .show {
                    title(title)
                    positiveButton(R.string.dialog_ok) { onPositiveButtonClicked() }
                    negativeButton(R.string.dialog_cancel)
                    setView(R.layout.dialog_generic_text_input)
                }.input(prefill = initialDeckName, displayKeyboard = true, waitForPositiveButton = false) { dialog, text ->

                    // defining the action of done button in ImeKeyBoard and enter button in physical keyBoard
                    val inputField = dialog.getInputField()
                    inputField.setOnEditorActionListener { _, actionId, event ->
                        if (actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                            when {
                                dialog.positiveButton.isEnabled -> {
                                    onPositiveButtonClicked()
                                }
                                text.isBlank() -> {
                                    dialog.getInputTextLayout().showSnackbar(
                                        context.getString(R.string.empty_deck_name),
                                        Snackbar.LENGTH_SHORT,
                                    )
                                }
                                else -> {
                                    dialog.getInputTextLayout().showSnackbar(
                                        context.getString(R.string.deck_already_exists),
                                        Snackbar.LENGTH_SHORT,
                                    )
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    // we need the fully-qualified name for subdecks
                    val maybeDeckName = fullyQualifyDeckName(dialogText = text)
                    // if the name is empty, it seems distracting to show an error
                    if (maybeDeckName == null || !Decks.isValidDeckName(maybeDeckName)) {
                        dialog.positiveButton.isEnabled = false
                        return@input
                    }
                    if (maybeDeckName != initialDeckName && deckExists(getColUnsafe, maybeDeckName)) {
                        dialog.getInputTextLayout().error = context.getString(R.string.deck_already_exists)
                        dialog.positiveButton.isEnabled = false
                        return@input
                    }
                    dialog.getInputTextLayout().error = null
                    dialog.positiveButton.isEnabled = true

                    // Users expect the ordering [1, 2, 10], but get [1, 10, 2]
                    // To fix: they need [01, 02, 10]. Show a hint to help them
                    dialog.getInputTextLayout().helperText =
                        if (text.containsNumberLargerThanNine()) {
                            context.getString(R.string.create_deck_numeric_hint)
                        } else {
                            null
                        }
                }
        shownDialog = dialog
        return dialog
    }

    /**
     * @return true if the collection contains a deck with the given name
     */
    private fun deckExists(
        col: Collection,
        name: String,
    ): Boolean = col.decks.byName(name) != null

    /**
     * Returns the fully qualified deck name for the provided input
     * @param dialogText The user supplied text in the dialog
     * @return [dialogText], or the deck name containing `::` in case of [DeckDialogType.SUB_DECK]
     */
    private fun fullyQualifyDeckName(dialogText: CharSequence) =
        when (deckDialogType) {
            DeckDialogType.DECK, DeckDialogType.FILTERED_DECK, DeckDialogType.RENAME_DECK -> dialogText.toString()
            DeckDialogType.SUB_DECK -> getColUnsafe.decks.getSubdeckName(parentId!!, dialogText.toString())
        }

    fun createSubDeck(
        did: DeckId,
        deckName: String?,
    ) {
        val deckNameWithParentName = getColUnsafe.decks.getSubdeckName(did, deckName)
        createDeck(deckNameWithParentName!!)
    }

    fun createDeck(deckName: String) {
        if (Decks.isValidDeckName(deckName)) {
            createNewDeck(deckName)
            // 11668: Display feedback if a deck is created
            displayFeedback(context.getString(R.string.deck_created))
        } else {
            Timber.d("CreateDeckDialog::createDeck - Not creating invalid deck name '%s'", deckName)
            displayFeedback(context.getString(R.string.invalid_deck_name), Snackbar.LENGTH_LONG)
        }
        // AlertDialog should be dismissed after the Keyboard 'Done' or Deck 'Ok' button is pressed
        shownDialog?.dismiss()
    }

    fun createFilteredDeck(deckName: String): Boolean {
        fun validFilteredDeckName(initialName: String): String {
            for (i in 0..10) {
                val name = initialName + "+".repeat(i)
                if (getColUnsafe.decks.byName(name) == null) return name
            }
            throw IllegalStateException("Could not generate valid name")
        }

        try {
            // create filtered deck
            Timber.i("CreateDeckDialog::createFilteredDeck...")
            val newDeckId = getColUnsafe.decks.newFiltered(validFilteredDeckName(deckName))
            Timber.d("Created filtered deck '%s'; id: %d", deckName, newDeckId)
            onNewDeckCreated(newDeckId)
        } catch (ex: IllegalStateException) {
            if (ex.message != "Could not generate valid name") {
                throw ex
            }
            displayFeedback(ex.localizedMessage ?: ex.message ?: "", Snackbar.LENGTH_LONG)
            return false
        } catch (ex: BackendDeckIsFilteredException) {
            displayFeedback(ex.localizedMessage ?: ex.message ?: "", Snackbar.LENGTH_LONG)
            return false
        }
        return true
    }

    private fun createNewDeck(deckName: String): Boolean {
        try {
            // create normal deck or sub deck
            Timber.i("CreateDeckDialog::createNewDeck")
            val newDeckId = getColUnsafe.decks.id(deckName)
            Timber.d("Created deck '%s'; id: %d", deckName, newDeckId)
            onNewDeckCreated(newDeckId)
        } catch (filteredAncestor: BackendDeckIsFilteredException) {
            Timber.w(filteredAncestor)
            return false
        }
        return true
    }

    private fun onPositiveButtonClicked() {
        if (deckName.isNotEmpty()) {
            when (deckDialogType) {
                DeckDialogType.DECK -> {
                    // create deck
                    createDeck(deckName)
                }
                DeckDialogType.RENAME_DECK -> {
                    renameDeck(deckName)
                }
                DeckDialogType.SUB_DECK -> {
                    // create sub deck
                    createSubDeck(parentId!!, deckName)
                }
                DeckDialogType.FILTERED_DECK -> {
                    // create filtered deck
                    createFilteredDeck(deckName)
                }
            }
        }
    }

    fun renameDeck(newDeckName: String) {
        if (!Decks.isValidDeckName(newDeckName)) {
            Timber.w("CreateDeckDialog::renameDeck not renaming deck to invalid name")
            Timber.d("invalid deck name: %s", newDeckName)
            displayFeedback(context.getString(R.string.invalid_deck_name), Snackbar.LENGTH_LONG)
        } else if (newDeckName != previousDeckName) {
            try {
                val decks = getColUnsafe.decks
                val deckId = decks.id(previousDeckName!!)
                decks.rename(decks.getLegacy(deckId)!!, newDeckName)
                onNewDeckCreated(deckId)
                // 11668: Display feedback if a deck is renamed
                displayFeedback(context.getString(R.string.deck_renamed))
            } catch (e: BackendDeckIsFilteredException) {
                Timber.w(e)
                // We get a localized string from libanki to explain the error
                displayFeedback(e.localizedMessage ?: e.message ?: "", Snackbar.LENGTH_LONG)
            }
        }
        // AlertDialog should be dismissed after the Keyboard 'Done' or Deck 'Ok' button is pressed
        shownDialog?.dismiss()
    }

    private fun displayFeedback(
        message: String,
        duration: Int = Snackbar.LENGTH_SHORT,
    ) {
        if (context is Activity) {
            context.showSnackbar(message, duration)
        } else {
            showThemedToast(context, message, duration == Snackbar.LENGTH_SHORT)
        }
    }
}

// to not match times. Example: "12:34:56"
// we use (?:[^:]|^) to ensure ":56" doesn't match
// we use (?:[^:]|$) to ensure "12:" doesn't match
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
fun CharSequence.containsNumberLargerThanNine(): Boolean = Regex("""(?:[^:]|^)[1-9]\d+(?:[^:]|$)""").find(this) != null
