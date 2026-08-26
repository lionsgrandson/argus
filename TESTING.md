# Acceptance test checklist

Before using the app overnight, test these cases with the actual two Android phones:

1. Start the Baby phone and confirm the Parent phone receives both audio and the live camera.
2. Lock the Baby phone screen for 10+ minutes and confirm both camera and audio continue on the Parent phone.
3. Wake the Baby phone again and confirm monitoring never stopped while the display was off.
4. Put the Parent phone on mobile data while the Baby phone remains on home Wi-Fi.
5. Walk far enough away to ensure the phones are not communicating over the local LAN/Bluetooth.
6. Confirm the camera orientation is correct and the image updates continuously enough for monitoring.
7. Speak softly, play normal room sounds, and confirm acceptable latency/audio quality.
8. Disconnect the Baby phone from the internet after monitoring has gone live; verify the Parent alarm occurs after roughly 8 seconds.
9. Restore internet and verify automatic reconnection and that camera/audio resume.
10. Verify Baby battery percentage and charging status update on the Parent phone.
11. Let the Baby battery fall below 20% while unplugged and verify the low-battery warning.
12. Lock the Parent screen and verify listening continues; unlock it and confirm the latest camera frames appear again.
13. Reboot the Baby phone; verify the Resume Baby Monitor notification appears and tapping it resumes through the app.
14. Rotate the pairing code and verify the old Parent credentials no longer work in the new room.
15. Verify the relay `/health` endpoint is reachable through HTTPS and that the app uses only the `wss://.../ws` URL.

Android camera behavior can vary by manufacturer, so the screen-off camera test on the exact Baby phone model is mandatory before relying on it overnight.
