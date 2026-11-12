# sensorrec

Records a phone's motion sensors over ADB without installing an app, to tune gesture
detectors against real data. `SensorRec.java` runs with `app_process` as the shell user and
prints CSV (time in ms from start):

| Row | Meaning |
|---|---|
| `L,t,x,y,z` | linear acceleration, m/s², 200 Hz |
| `A,t,x,y,z` | raw accelerometer, ~500 Hz |
| `G,t,x,y,z` | gyroscope, rad/s, 200 Hz |
| `CHOP,t,1.0` | Motorola's hardware chop-chop gesture fired (only on Moto phones) |
| `TORCH,t,on\|off` | flashlight state (polled from `flashlight_enabled`) |

## Record

```sh
SDK=~/Library/Android/sdk; OUT=/tmp/sensorrec; mkdir -p $OUT/classes
javac --release 11 -cp $SDK/platforms/android-34/android.jar -d $OUT/classes tools/sensorrec/SensorRec.java
$SDK/build-tools/36.0.0/d8 --min-api 34 --lib $SDK/platforms/android-34/android.jar --output $OUT $OUT/classes/*.class
adb push $OUT/classes.dex /data/local/tmp/sensorrec.dex
adb shell CLASSPATH=/data/local/tmp/sensorrec.dex app_process / SensorRec 180 > $OUT/rec.csv   # seconds
```

## Analyze

- `python3 -I tools/sensorrec/analyze.py rec.csv` : spikes around every `CHOP` event, stats,
  motion bursts that did not fire. Add event indexes (`7`) or times (`@52129`) for a 10 ms
  trace of linear accel, raw accel and gyro.
- `CHOP_REC=$OUT/rec.csv make test-native` : `ChopReplayTest` runs the recording through
  `ChopDetector` and prints, per threshold, Motorola events caught / missed and extra fires.

## 2026-11-12 recording (motorola edge 30, Moto Actions chop-chop on)

22 Motorola chop-chops (18 normal, 4 gentle), 1 gentle attempt Motorola ignored. A chop-chop
is a whip along device x: lobes + − + − (~75, −78, +78, −78 m/s² when strong, 34–67 when
gentle), peaks 70–280 ms apart, gyro z ±10–20 rad/s. Motorola fires at about the third lobe
and turns the torch on 70–180 ms later. The ignored attempt had lobes 24–37 m/s².
