// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.IdentityCredential.AccountSelectorPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.IdentityCredential.PrivacyPolicyPrompt;
import org.mozilla.geckoview.GeckoSession.PromptDelegate.IdentityCredential.ProviderSelectorPrompt;

/**
 * Explicit forwarding for all 25 callbacks in the pinned GeckoView 158 PromptDelegate.
 *
 * <p>These are Java default methods. Kotlin interface delegation did not generate forwarding
 * overrides for them, so an adapter declared with {@code PromptDelegate by original} inherited
 * GeckoView's default cancellation instead of reaching the Android Components delegate.
 * Keep this list aligned with the pinned interface when updating GeckoView; the file adapter only
 * overrides onFilePrompt, and every other prompt must retain the actual original delegate.
 */
@UiThread
abstract class ForwardingGeckoPromptDelegate implements PromptDelegate {
    private final PromptDelegate original;

    ForwardingGeckoPromptDelegate(@NonNull PromptDelegate original) {
        this.original = original;
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onAlertPrompt(
            @NonNull GeckoSession session, @NonNull AlertPrompt prompt) {
        return original.onAlertPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onBeforeUnloadPrompt(
            @NonNull GeckoSession session, @NonNull BeforeUnloadPrompt prompt) {
        return original.onBeforeUnloadPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onRepostConfirmPrompt(
            @NonNull GeckoSession session, @NonNull RepostConfirmPrompt prompt) {
        return original.onRepostConfirmPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onButtonPrompt(
            @NonNull GeckoSession session, @NonNull ButtonPrompt prompt) {
        return original.onButtonPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onFolderUploadPrompt(
            @NonNull GeckoSession session, @NonNull FolderUploadPrompt prompt) {
        return original.onFolderUploadPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onWebAuthnRelatedOriginPrompt(
            @NonNull GeckoSession session, @NonNull WebAuthnRelatedOriginPrompt prompt) {
        return original.onWebAuthnRelatedOriginPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onTextPrompt(
            @NonNull GeckoSession session, @NonNull TextPrompt prompt) {
        return original.onTextPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onAuthPrompt(
            @NonNull GeckoSession session, @NonNull AuthPrompt prompt) {
        return original.onAuthPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onChoicePrompt(
            @NonNull GeckoSession session, @NonNull ChoicePrompt prompt) {
        return original.onChoicePrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onColorPrompt(
            @NonNull GeckoSession session, @NonNull ColorPrompt prompt) {
        return original.onColorPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onDateTimePrompt(
            @NonNull GeckoSession session, @NonNull DateTimePrompt prompt) {
        return original.onDateTimePrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onFilePrompt(
            @NonNull GeckoSession session, @NonNull FilePrompt prompt) {
        return original.onFilePrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onPopupPrompt(
            @NonNull GeckoSession session, @NonNull PopupPrompt prompt) {
        return original.onPopupPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onRedirectPrompt(
            @NonNull GeckoSession session, @NonNull RedirectPrompt prompt) {
        return original.onRedirectPrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onSharePrompt(
            @NonNull GeckoSession session, @NonNull SharePrompt prompt) {
        return original.onSharePrompt(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onLoginSave(
            @NonNull GeckoSession session,
            @NonNull AutocompleteRequest<Autocomplete.LoginSaveOption> request) {
        return original.onLoginSave(session, request);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onAddressSave(
            @NonNull GeckoSession session,
            @NonNull AutocompleteRequest<Autocomplete.AddressSaveOption> request) {
        return original.onAddressSave(session, request);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onRequestCertificate(
            @NonNull GeckoSession session, @NonNull CertificateRequest request) {
        return original.onRequestCertificate(session, request);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onCreditCardSave(
            @NonNull GeckoSession session,
            @NonNull AutocompleteRequest<Autocomplete.CreditCardSaveOption> request) {
        return original.onCreditCardSave(session, request);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onLoginSelect(
            @NonNull GeckoSession session,
            @NonNull AutocompleteRequest<Autocomplete.LoginSelectOption> request) {
        return original.onLoginSelect(session, request);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onSelectIdentityCredentialProvider(
            @NonNull GeckoSession session, @NonNull ProviderSelectorPrompt prompt) {
        return original.onSelectIdentityCredentialProvider(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onSelectIdentityCredentialAccount(
            @NonNull GeckoSession session, @NonNull AccountSelectorPrompt prompt) {
        return original.onSelectIdentityCredentialAccount(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onShowPrivacyPolicyIdentityCredential(
            @NonNull GeckoSession session, @NonNull PrivacyPolicyPrompt prompt) {
        return original.onShowPrivacyPolicyIdentityCredential(session, prompt);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onCreditCardSelect(
            @NonNull GeckoSession session,
            @NonNull AutocompleteRequest<Autocomplete.CreditCardSelectOption> request) {
        return original.onCreditCardSelect(session, request);
    }

    @Override
    public @Nullable GeckoResult<PromptResponse> onAddressSelect(
            @NonNull GeckoSession session,
            @NonNull AutocompleteRequest<Autocomplete.AddressSelectOption> request) {
        return original.onAddressSelect(session, request);
    }
}
