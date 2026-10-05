package dev.rigspecs.ui.csv

import androidx.compose.runtime.Composable

/** Opening and saving CSV files where the user chooses. Platform code implements it. */
interface CsvFiles {
    /** Lets the user pick a CSV file; its name and text go to `onPicked`. */
    fun pick()

    /** Lets the user choose where to save [text], starting from [suggestedName]. */
    fun save(suggestedName: String, text: String)
}

/** [onSaved] gets a short description of where the file went; [onError] a sentence for the user. */
@Composable
expect fun rememberCsvFiles(
    onPicked: (name: String, text: String) -> Unit,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit,
): CsvFiles
