/* Per-lane colour scaled by one packed opacity word. The rasterizer
 * writes the interpolated colour at slice 3. The per-draw uniform at
 * byte 288 of the current kernarg bank is the opacity, and the low
 * byte is the factor. Each channel is (colour * opacity) >> 8. The
 * product is stored at slice 6. */
    .option norvc
    .text
    .globl shader
shader:
    .word 0x00241293 /* slli x5, x8, 2 */
    .word 0x005082b3 /* add x5, x1, x5 */
    .word 0xc1027057 /* vsetivli x0, 4, e32, m1, ta, ma */
    .word 0x06028313 /* addi x6, x5, 96 */
    .word 0x02036087 /* vle32.v v1, (x6) color */
    .word 0x0ff00393 /* addi x7, x0, 255 */
    .word 0x1200a483 /* lw x9, 288(x1) opacity */
    .word 0x5e04c357 /* vmv.v.x v6, x9 */
    .word 0x2663c357 /* vand.vx v6, v6, x7 */
    .word 0xa21c3257 /* vsrl.vi v4, v1, 24 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0x96432257 /* vmul.vv v4, v4, v6 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x964c3257 /* vsll.vi v4, v4, 24 */
    .word 0x2a4201d7 /* vor.vv v3, v4, v4 */
    .word 0xa2183257 /* vsrl.vi v4, v1, 16 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0x96432257 /* vmul.vv v4, v4, v6 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x96483257 /* vsll.vi v4, v4, 16 */
    .word 0x2a3201d7 /* vor.vv v3, v3, v4 */
    .word 0xa2143257 /* vsrl.vi v4, v1, 8 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0x96432257 /* vmul.vv v4, v4, v6 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x96443257 /* vsll.vi v4, v4, 8 */
    .word 0x2a3201d7 /* vor.vv v3, v3, v4 */
    .word 0x2a108257 /* vor.vv v4, v1, v1 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0x96432257 /* vmul.vv v4, v4, v6 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x2a3201d7 /* vor.vv v3, v3, v4 */
    .word 0x0c028313 /* addi x6, x5, 192 */
    .word 0x020361a7 /* vse32.v v3, (x6) out-color */
    .insn i 0x73, 0, zero, zero, 0x305
