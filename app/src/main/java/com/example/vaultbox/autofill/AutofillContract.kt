package com.example.vaultbox.autofill

import android.content.Context
import android.widget.RemoteViews
import com.example.vaultbox.R

object AutofillContract {
    const val EXTRA_USERNAME_ID = "com.example.vaultbox.autofill.USERNAME_ID"
    const val EXTRA_PASSWORD_ID = "com.example.vaultbox.autofill.PASSWORD_ID"
    const val EXTRA_FOCUSED_ID = "com.example.vaultbox.autofill.FOCUSED_ID"
    const val EXTRA_RESPONSE_AUTH = "com.example.vaultbox.autofill.RESPONSE_AUTH"
    const val EXTRA_WEB_DOMAIN = "com.example.vaultbox.autofill.WEB_DOMAIN"
    const val EXTRA_APP_PACKAGE = "com.example.vaultbox.autofill.APP_PACKAGE"

    fun presentation(context: Context, title: String, subtitle: String? = null): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofillTitle, title)
            setTextViewText(R.id.autofillSubtitle, subtitle.orEmpty())
        }
}
