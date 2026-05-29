import math
import os

SINC_ZERO_CROSSINGS = 16
SINC_OVERSAMPLING = 1024
SINC_LUT_SIZE = SINC_ZERO_CROSSINGS * SINC_OVERSAMPLING

# Calculate values
values = []
for i in range(SINC_LUT_SIZE):
    x = i / SINC_OVERSAMPLING
    if x == 0.0:
        val = 1.0
    else:
        pi_x = math.pi * x
        sinc = math.sin(pi_x) / pi_x
        window = 0.54 + 0.46 * math.cos(math.pi * x / SINC_ZERO_CROSSINGS)
        val = sinc * window
    values.append(val)

script_dir = os.path.dirname(os.path.abspath(__file__))

h_file_path = os.path.join(script_dir, 'sinc_lut.h')
c_file_path = os.path.join(script_dir, 'sinc_lut.c')

with open(h_file_path, 'w') as f:
    f.write('''#ifndef SINC_LUT_H
#define SINC_LUT_H

#define SINC_ZERO_CROSSINGS 16
#define SINC_OVERSAMPLING 1024
#define SINC_LUT_SIZE (SINC_ZERO_CROSSINGS * SINC_OVERSAMPLING)

// Extern declaration ensures exactly one memory footprint exists in .rodata
extern const float SINC_LUT[SINC_LUT_SIZE];

#endif // SINC_LUT_H
''')

with open(c_file_path, 'w') as f:
    f.write('''#include "sinc_lut.h"

const float SINC_LUT[SINC_LUT_SIZE] = {
''')
    for i, val in enumerate(values):
        f.write(f'    {val:.9f}f,')
        if (i + 1) % 5 == 0:
            f.write('\n')
        else:
            f.write(' ')
    f.write('\n};\n')

print("Generated sinc_lut.h and sinc_lut.c successfully.")
