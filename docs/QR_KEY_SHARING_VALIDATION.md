# QR key sharing validation

## Automated coverage

- Version 1 payload round-trip with fake credentials only.
- Malformed, unrelated, unsupported-version, and oversized payload rejection.
- Imported values use the same trimmed key-change flow as manual entry.
- Changed values cancel UI work, clear Gemini conversation state, and cancel processing; unchanged values do not.

On 2026-09-13, `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug` completed successfully. The unit suite ran 50 tests with no failures or errors. Lint completed with the repository's existing non-fatal warnings.

## Physical-device checks

These checks require two screens or prepared images and remain outstanding unless explicitly recorded below:

- Scan a QR displayed on another screen, review the masked key, and tap Save.
- Import a valid QR screenshot with Android's photo picker.
- Deny camera permission and confirm manual entry and image import remain available.
- Cancel the camera scanner and photo picker and confirm the saved key is unchanged.
- Try an unreadable, unsupported, and oversized QR image.

No Android device or emulator was attached through ADB during the 2026-09-13 validation, so all physical-device checks above remain outstanding.

No live Gemini request is required to validate QR transport. Use fake credentials for QR testing; never place a real credential in test artifacts, logs, screenshots, or this document.
