# Input prediction, reprojection and frame generation: what can and can't help a PS5 stream

Researched November 2026. The question: can a Remote Play client predict input, warp frames or generate frames to cut the delay you feel? Short answer: **the techniques that work in local games need the game's cooperation, which a PS5 stream doesn't give us.** Here is each idea, what it needs, and where Hayai stands.

## 1. Input prediction ("negative latency")

**What it is.** The game guesses your next input and starts on it early, or the client predicts the game's state and shows it before the server confirms. Online games do this, and Google promised it for Stadia.

**Why it doesn't fit Remote Play.** Prediction needs to run the game's logic (or a model of it) on the client. Research on cloud gaming points out that client-side prediction doesn't apply when the client only decodes and shows the stream from the server. Published latency compensation work changes the game itself (for example, assisting the player's aim), and Remote Play gives us no hook into PS5 games.

**What Hayai does instead.** It cuts the time to get each real input to the console: unbuffered input, the feedback sender firing on every change, DSCP-marked packets, and threads on the fast cores.

## 2. Reprojection / "Frame Warp" (NVIDIA Reflex 2 style)

**What it is.** Just before display, the last frame is shifted to match the newest camera input, so turning the camera *looks* instant even though the game hasn't caught up. Reflex 2's Frame Warp renders a frame, works out the updated camera position, and warps the frame to it. VR headsets have done this for years.

**Why it's hard here.**
- It changes only **camera** latency, not game latency. Jumps, shots and button presses still take the full trip.
- The warp has to know exactly how far the camera turns per stick movement. That depends on each game's sensitivity, acceleration and dead zones, which the client can't see. A wrong guess makes the picture slide, then snap back when the real frame arrives.
- Uncovered edges need filling. Even NVIDIA, with full access to the game, still hadn't shipped Frame Warp about 18 months after announcing it, and demos show edge artifacts.

**Status.** Not built. It could become an experimental per-game "camera warp" with manual calibration (see the roadmap), but it would be a novelty with visible artifacts, not a general fix.

## 3. Frame generation

| Kind | How | Effect on delay |
|---|---|---|
| **Interpolation** (DLSS FG, Lossless Scaling, most TV "motion smoothing") | Makes a frame *between* two real ones | **Adds** at least one frame of delay (16.7 ms at 60 fps), because it has to wait for the next real frame. The opposite of the goal. |
| **Extrapolation** (Qualcomm Adreno Frame Motion Engine, Virtual Desktop's Synchronous Spacewarp) | Predicts the *next* frame from the motion so far | Adds no wait, and can fill the Odin's 120 Hz with predicted in-between frames. The game still reacts at the same speed: it looks smoother, it doesn't respond faster. Guesses go wrong on sudden changes. |
| **Qualcomm Adreno Neural Fusion** (2026) | AI frame generation | Needs the game's depth and motion vectors, and a Snapdragon 8 Elite Gen 6 or newer. Not usable on a video stream or on the Odin's 8 Gen 2. |

The Snapdragon 8 Gen 2's Adreno 740 does have Qualcomm's Frame Motion Engine. But a stream has no depth or motion vectors, and MediaCodec doesn't expose the encoder's motion vectors, so extrapolation would need optical flow computed from the video itself.

**Verdict.** Interpolation would make Hayai feel *worse*. Extrapolation to 120 fps is a possible future option for smoothness only, off by default and clearly labelled as such. It wouldn't reduce input delay.

## What *does* reduce delay, and is in Hayai

| Technique | Where it saves | Status |
|---|---|---|
| 120 Hz display while streaming | Wait for refresh, scan-out | Done (v0.1) |
| Unbuffered input dispatch | Android's per-vsync input batching | Done (v0.1) |
| Qualcomm low latency decoder at max clock | Decode | Done (v0.1) |
| Snapdragon software-fence early output | Decode → display handoff | **Done (v0.2)** |
| Newest frame wins | Backlogs after Wi-Fi hiccups | Done (v0.1) |
| **Tearing mode** (front buffer rendering) | SurfaceFlinger latch + one or more refreshes | **Experimental (v0.2)** |
| **Exclusive (MMAP) audio, 2-burst buffer** | Audio mixer and buffer | **Done (v0.2)** |
| DSCP EF on stream packets | Wi-Fi airtime contention for input | **Done (v0.2)** |
| Fast cores only for hot threads | Scheduling and wake-up on little cores | **Done (v0.2)** |
| Direct evdev input (root) | Android input stack | Planned |

## Sources
- Cloud gaming lag compensation: Springer, *Lag Compensation for First-Person Shooter Games in Cloud Gaming*; ResearchGate, *Increasing Player Performance and Game Experience in High Latency Systems*; US patents 11601670 / 11503332 (player input motion compensation, which needs server motion vectors)
- Reflex 2 Frame Warp: Tom's Hardware, Hardware Busters ("18 months and still hasn't shipped"), Blur Busters forums
- Qualcomm: Snapdragon 8 Gen 3 product brief (Adreno Frame Motion Engine 2.0), Adreno Motion Engine and Virtual Desktop frame extrapolation (developer.qualcomm.com), Adreno Neural Fusion SDK blog (Sept 2026)
