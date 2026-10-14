# Make terminal output fit your phone

Herdr on the Go **0.2.2** can temporarily narrow the desktop terminal pane to
your phone's width. Terminal applications that respond to a resize can rewrap
their output, making long lines easier to read without shrinking the font.

## Use it

1. Install the app and Windows bridge **0.3.2** from the
   [v0.2.2 release](https://github.com/RazvanManolache/herdr-on-the-go/releases/tag/v0.2.2).
2. Open a chat in a Herdr tab containing one pane. **Fit width** is enabled by
   default. The app measures the width using the current font size.
3. On the PC, a temporary notice pane appears beside the agent's pane. It
   explains that a phone is viewing the tab and ignores keyboard input.
4. Use **A− / A+** as usual; the fitting width adjusts after the font changes.
5. Open **⋯** beside the message box and tap **Fit width: on** to turn it off.
   Tap again to enable it. The preference is saved on the phone.

<img src="images/screenshots/fit-menu.png" width="280" alt="Fictional demonstration of the Fit width toggle in Message options">

Switching tabs or computers, disconnecting, or leaving the app releases the
old fit. Only the pane created by the bridge is closed; existing desktop splits
are preserved and tabs with multiple panes are not fitted.

The visible fitted tab sends a heartbeat every minute. If the phone app stops
unexpectedly, the bridge's snapshot poller cleans up its recorded abandoned fit
after about five minutes. The next attach also checks for leftover notice panes.
Cleanup depends on the bridge poller running and the Herdr session being reachable.

## Update the PC bridge

Use the existing [SSH installation guide](https://github.com/RazvanManolache/herdr-bridge#updates-backups-and-removal)
or [paired installation guide](https://github.com/RazvanManolache/herdr-bridge/blob/main/docs/PAIRING.md)
for your connection type. Reconnect the app so its responder loads the new code.
Pairings and saved connections are retained.

The paired installer reuses an already-running snapshot poller. To activate the
new abandoned-fit cleanup after a paired upgrade, sign out of Windows and back
in when convenient, or restart only this installation's `snapshot-poller.py`
process following the bridge guide. A currently running older poller does not
load the new cleanup code automatically. Updating the app alone does not install
new bridge code on the PC.

## Terminal colors

The app now renders the terminal's color and style information, with palettes
adapted for light and dark themes. Searching and copying operate on readable
text without escape sequences. The public screenshots contain only invented
output and credentials.
