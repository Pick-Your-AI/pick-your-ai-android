package ai.pickyour.android.preview

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pickyour.core.configuration.ClientEnvironmentAdmission
import ai.pickyour.core.configuration.ConfigurationFailure
import ai.pickyour.core.configuration.EnvironmentAdmission
import java.io.InputStream

/** One admission before any editor, local store or synthetic artifact is opened. */
internal object EnvironmentBootstrap {
    fun read(
        activity: Activity,
        selectedEnvironment: String? = BuildConfig.PYAI_ENVIRONMENT,
        openConfiguration: () -> InputStream = { activity.assets.open("client-environment.json") },
    ): EnvironmentAdmission = try {
        openConfiguration().use { input ->
            ClientEnvironmentAdmission.read(selectedEnvironment, activity.packageName,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, input)
        }
    } catch (_: Exception) {
        // Never retain the provider message, input path, rejected URL or configuration values.
        EnvironmentAdmission.Rejected(ConfigurationFailure.MISSING_CONFIGURATION)
    }

    fun admit(activity: Activity, admission: EnvironmentAdmission): Boolean {
        if (admission === EnvironmentAdmission.SyntheticPreview) return true
        val failure = (admission as EnvironmentAdmission.Rejected).reason
        val explanation = when (failure) {
            ConfigurationFailure.REAL_ENVIRONMENT_UNCONFIGURED -> R.string.environment_real_unconfigured
            ConfigurationFailure.UNKNOWN_ENVIRONMENT, ConfigurationFailure.ENVIRONMENT_MISMATCH -> R.string.environment_wrong_mode
            ConfigurationFailure.APPLICATION_MISMATCH -> R.string.environment_wrong_application
            ConfigurationFailure.CONTRACT_MISMATCH, ConfigurationFailure.CATALOG_MISMATCH -> R.string.environment_wrong_contract
            ConfigurationFailure.PREVIEW_REMOTE_CONFIGURATION, ConfigurationFailure.CAPABILITY_MISMATCH -> R.string.environment_wrong_capabilities
            else -> R.string.environment_invalid_configuration
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.environment_blocked_title)
                textSize = 22f
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
            addView(TextView(context).apply {
                setText(explanation)
                textSize = 18f
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            addView(TextView(context).apply { setText(R.string.environment_recovery); textSize = 18f })
            addView(Button(context).apply {
                setText(R.string.environment_close)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { activity.finish() }
            })
        }
        activity.setContentView(ScrollView(activity).apply { addView(content) })
        return false
    }
}
