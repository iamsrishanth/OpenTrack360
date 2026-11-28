package me.srishanth.opentrack360.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import me.srishanth.opentrack360.ui.components.MemberCard
import me.srishanth.opentrack360.ui.components.MemberScreen
import me.srishanth.opentrack360.ui.components.PrimaryAction
import me.srishanth.opentrack360.ui.components.StatusBanner
import me.srishanth.opentrack360.ui.model.DataCondition

data class SignInUiState(
    val username: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val accountDisabled: Boolean = false,
)

data class PasswordFormUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmation: String = "",
    val submitting: Boolean = false,
    val errorMessage: String? = null,
)

@Composable
fun SignInScreen(
    state: SignInUiState,
    invitationPending: Boolean = false,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: (username: String, password: String) -> Unit,
    onRecoveryHelp: () -> Unit,
    onOpenGuestLink: () -> Unit,
    oauthAvailable: Boolean = false,
    oauthLaunching: Boolean = false,
    onOAuthSignIn: () -> Unit = {},
) {
    MemberScreen(
        title = "Sign in to OpenTrack360",
        subtitle = "Use the username and temporary password provided by your Circle administrator.",
    ) {
        MemberCard {
            if (invitationPending) {
                StatusBanner(
                    DataCondition.PENDING,
                    "Circle invitation received",
                    "Sign in as the account that should join. OpenTrack360 will ask you to confirm before accepting the invitation.",
                )
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            if (state.accountDisabled) {
                StatusBanner(
                    DataCondition.UNAVAILABLE,
                    "Account unavailable",
                    "This account cannot sign in. Contact the administrator who provisioned it for help.",
                )
            } else {
                state.errorMessage?.let {
                    StatusBanner(DataCondition.ERROR, "Sign-in failed", it)
                }
            }
            PrimaryAction(
                label = if (state.submitting) "Signing in…" else "Sign in",
                onClick = { onSignIn(state.username.trim(), state.password) },
                enabled = !state.submitting && state.username.isNotBlank() && state.password.isNotEmpty() && !state.accountDisabled,
                modifier = Modifier.fillMaxWidth(),
            )
            if (oauthAvailable) {
                TextButton(
                    onClick = onOAuthSignIn,
                    enabled = !state.submitting && !oauthLaunching && !state.accountDisabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (oauthLaunching) "Opening browser sign-in…" else "Sign in with OpenTrack360 in browser")
                }
            }
            TextButton(onClick = onRecoveryHelp, modifier = Modifier.fillMaxWidth()) { Text("Get account recovery help") }
        }
        TextButton(onClick = onOpenGuestLink, modifier = Modifier.fillMaxWidth()) {
            Text("Open a shared trip link")
        }
    }
}

@Composable
fun TemporaryPasswordChangeScreen(
    username: String,
    state: PasswordFormUiState,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onChangePassword: (currentPassword: String, newPassword: String) -> Unit,
    onSignOut: () -> Unit,
) {
    MemberScreen(
        title = "Set a new password",
        subtitle = "This temporary password must be replaced before Circle information can open.",
    ) {
        MemberCard {
            Text("Signed in as $username", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.currentPassword,
                onValueChange = onCurrentPasswordChange,
                label = { Text("Temporary password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            OutlinedTextField(
                value = state.newPassword,
                onValueChange = onNewPasswordChange,
                label = { Text("New password") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Use 12–128 characters.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            OutlinedTextField(
                value = state.confirmation,
                onValueChange = onConfirmationChange,
                label = { Text("Confirm new password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Password not changed", it) }
            StatusBanner(
                DataCondition.PENDING,
                "A fresh sign-in is required",
                "After the password changes, the current session ends. Sign in again with the new password before opening member data.",
            )
            PrimaryAction(
                label = if (state.submitting) "Changing password…" else "Change password",
                onClick = { onChangePassword(state.currentPassword, state.newPassword) },
                enabled = !state.submitting && state.currentPassword.isNotEmpty() && state.newPassword.length >= 12 && state.newPassword.length <= 128 && state.newPassword == state.confirmation,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}

@Composable
fun PasswordChangeSettingsScreen(
    state: PasswordFormUiState,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onChangePassword: (currentPassword: String, newPassword: String) -> Unit,
) {
    MemberScreen(
        title = "Change password",
        subtitle = "A successful change signs out active sessions. Sign in again with your new password.",
    ) {
        MemberCard {
            OutlinedTextField(
                value = state.currentPassword,
                onValueChange = onCurrentPasswordChange,
                label = { Text("Current password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            OutlinedTextField(
                value = state.newPassword,
                onValueChange = onNewPasswordChange,
                label = { Text("New password") },
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Use 12–128 characters.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            OutlinedTextField(
                value = state.confirmation,
                onValueChange = onConfirmationChange,
                label = { Text("Confirm new password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
            )
            state.errorMessage?.let { StatusBanner(DataCondition.ERROR, "Password not changed", it) }
            PrimaryAction(
                label = if (state.submitting) "Changing password…" else "Change password",
                onClick = { onChangePassword(state.currentPassword, state.newPassword) },
                enabled = !state.submitting && state.currentPassword.isNotEmpty() && state.newPassword.length in 12..128 && state.newPassword == state.confirmation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun AccountRecoveryHelpScreen(
    helpMessage: String,
    onOpenHelp: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    MemberScreen(
        title = "Account recovery",
        subtitle = "OpenTrack360 accounts are provisioned by a website administrator.",
    ) {
        StatusBanner(
            DataCondition.UNAVAILABLE,
            "Ask the account administrator",
            helpMessage.ifBlank { "Contact the administrator who created your account. Self-service password reset is not available here." },
        )
        MemberCard {
            Text("Do not share your current or temporary password in a message.")
            PrimaryAction("Open help", onOpenHelp, Modifier.fillMaxWidth())
            TextButton(onClick = onBackToSignIn, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in") }
        }
    }
}
