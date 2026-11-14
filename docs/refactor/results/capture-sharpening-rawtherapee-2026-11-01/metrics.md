> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

| Case | Mean absolute RGB error | Maximum RGB error | PSNR (white = 1) | Sharpening-delta correlation |
|---|---:|---:|---:|---:|
| preset-2-cfa-0 | 0.0001219 | 0.0009761 | 75.02 dB | 0.99996923 |
| preset-3-cfa-1 | 0.0001127 | 0.0009761 | 75.55 dB | 0.99998538 |
| preset-4-cfa-2 | 0.0001085 | 0.0009760 | 75.86 dB | 0.99998698 |
| preset-6-cfa-3 | 0.0001134 | 0.0009738 | 75.44 dB | 0.99998787 |
| kernel-0.45 | 0.0001174 | 0.0013101 | 74.43 dB | 0.99981747 |
| kernel-0.8 | 0.0001220 | 0.0013238 | 74.20 dB | 0.99997874 |
| kernel-1.1 | 0.0001287 | 0.0012571 | 73.82 dB | 0.99998443 |
| kernel-1.3 | 0.0001115 | 0.0018011 | 74.52 dB | 0.99998486 |
| kernel-1.6 | 0.0001123 | 0.0018899 | 74.45 dB | 0.99998577 |
| offset-unlimited | 0.0001472 | 0.0330540 | 68.61 dB | 0.99999023 |
| strips-bands | 0.0000997 | 0.0009785 | 75.06 dB | 0.99993665 |
| full-resolution | 0.0000569 | 0.0009837 | 77.88 dB | 0.99993091 |
| rt-default-20 | 0.0001135 | 0.0011542 | 74.71 dB | 0.99998126 |
| rt-corner-boost-0.5 | 0.0001135 | 0.0022129 | 74.48 dB | 0.99998148 |
