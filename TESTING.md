# ARGUS acceptance test checklist

Before relying on ARGUS for a long session, test these cases with the actual two Android phones:

1. Start the Child phone and confirm the Parent phone receives both audio and the live camera.
2. Lock the Child phone screen for 10+ minutes and confirm both camera and audio continue on the Parent phone.
3. Swipe ARGUS away from the Child phone's recent-apps screen and confirm transmission continues.
4. Wake the Child phone again and confirm monitoring never stopped while the display was off.
5. Put the Parent phone on mobile data while the Child phone remains on home Wi-Fi.
6. Walk far enough away to ensure the phones are not communicating over the local LAN/Bluetooth.
7. Confirm the camera orientation is correct and the image updates continuously enough for monitoring.
8. Speak softly, play normal room sounds, and confirm acceptable latency/audio quality.
9. Disconnect the Child phone from the internet after monitoring has gone live; verify the Parent alarm occurs after roughly 8 seconds.
10. Restore internet and verify automatic reconnection and that camera/audio resume.
11. Verify Child battery percentage and charging status update on the Parent phone.
12. Let the Child battery fall below 20% while unplugged and verify the low-battery warning.
13. Lock the Parent screen and verify listening continues; unlock it and confirm the latest camera frames appear again.
14. Reboot the Child phone while transmission is active. On Android versions that permit automatic background camera/microphone restart, verify ARGUS resumes automatically. On newer Android versions that enforce the platform restriction, verify the **Resume ARGUS** notification appears and tapping it resumes transmission immediately without repairing or reconfiguring.
15. Open **Advanced settings → Background battery settings** and verify ARGUS can be set to the least restrictive battery mode available on the device.
16. Rotate the pairing QR and verify the old Parent credentials no longer work in the new room.
17. Verify the relay `/health` endpoint is reachable through HTTPS and that the app uses only the configured `wss://.../ws` URL.

Android camera/background behavior can vary by manufacturer, so the screen-off, task-removal, battery-mode, and reboot tests on the exact Child phone model are mandatory for reliability testing.
