CUE VIDEO - Android app (Kotlin) - Stage 5 + new look
Port of the desktop engine make_video.py. See porting_spec_make_video_android.txt.

NEW LOOK AND ONE INPUT BUTTON
- Dark premium design: gradient buttons, rounded panels, status dots.
- One "+ Add project" button. It opens a small menu: "Files or ZIP bundle" or "Folder". You can pick
  everything at once: pictures, ZIP bundles, the two timestamp SRT files, the script (.txt), the voice-over
  and scene clips. Each file goes to its own field automatically (see "What I found"). You can add more
  files later; they are merged. "Change" next to a field lets you pick that file by hand.
- Sorting rules: audio with "click" in its name is the click sound, the biggest other audio is the
  voice-over; the SRT named "master" is the master, the other is the original (else by TurboScribe text /
  cue count); a .txt is the script (name with "script" first); videos are scene clips; pictures by number.
- Tools (timing table, slide preview, audio test) are under "Show tools and details".

STAGE 5: "7. Export video"
- Press "Export video", choose a folder. The app mixes the audio, draws every frame with OpenGL into the
  hardware H.264 encoder, adds the AAC audio, moves the moov box to the front (faststart) and saves
  <name>.mp4 and <name>_report.csv in that folder. <name> is the master SRT name without "master".
- Scene clips are decoded and drawn with black bars; a short clip holds its last frame, a long one is cut.
- Progress bar, Cancel button, screen stays on while exporting.

HOW TO BUILD THE APK (GitHub Actions)
1. Make a free GitHub account, create a new repository (private is fine).
2. Upload ALL files and folders of this ZIP to it (keep the folder layout, including the hidden .github folder).
3. Open the Actions tab. The "Build APK" run starts by itself (or press Run workflow).
4. When it is green, open the run and download "CueVideo-debug-apk" at the bottom. Unzip it, copy app-debug.apk to the phone, open it, allow "install unknown apps".
If the run is red, open it, copy the red error text and paste it to Claude.

TEST LIST FOR STAGE 5 (on the phone)
1. Small project first: 5-10 images, one heading cue, one subscribe cue, a short voice-over. Export at 30 fps.
2. Play the MP4: cuts on the right frames, clicks at each cut, heading zooms and fades in, subscribe cue is black.
3. Open the report CSV and compare start times with the Timing section (cue times).
4. Add one scene clip named like 3.mp4: it must replace cue 3, be letterboxed, muted, and the click comes at its start.
   Also try a clip shorter than its cue (last frame held) and one longer (cut). Try a phone-recorded vertical clip.
5. Check the file starts playing at once when shared/uploaded (moov at the front).
6. Press Cancel in the middle: no half-written files should be left in the chosen folder.
7. Try 60 fps once. Watch phone temperature.
