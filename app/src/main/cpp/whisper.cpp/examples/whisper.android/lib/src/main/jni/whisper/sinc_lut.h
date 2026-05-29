#ifndef SINC_LUT_H
#define SINC_LUT_H

#define SINC_ZERO_CROSSINGS 16
#define SINC_OVERSAMPLING 1024
#define SINC_LUT_SIZE (SINC_ZERO_CROSSINGS * SINC_OVERSAMPLING)

// Extern declaration ensures exactly one memory footprint exists in .rodata
extern const float SINC_LUT[SINC_LUT_SIZE];

#endif // SINC_LUT_H
