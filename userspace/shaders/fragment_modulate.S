/* Per-lane texture sample modulated by the interpolated colour.
 * UVs are Q16.16 at slices 4 and 5. The rasterizer writes the packed
 * colour at slice 3. Each channel is (texel * colour) >> 8, matching
 * the fixed-function modulator. The product is stored at slice 6. */
    .option norvc
    .text
    .globl shader
shader:
    .word 0x00241293 /* slli x5, x8, 2 */
    .word 0x005082b3 /* add x5, x1, x5 */
    .word 0xc1027057 /* vsetivli x0, 4, e32, m1, ta, ma */
    .word 0x08028313 /* addi x6, x5, 128 */
    .word 0x02036087 /* vle32.v v1, (x6) u */
    .word 0x0a028313 /* addi x6, x5, 160 */
    .word 0x02036107 /* vle32.v v2, (x6) v */
    .word 0x0620812b /* vtex.sample v2, v1, v2 */
    .word 0x06028313 /* addi x6, x5, 96 */
    .word 0x02036087 /* vle32.v v1, (x6) color */
    .word 0x0ff00393 /* addi x7, x0, 255 */
    .word 0xa22c3257 /* vsrl.vi v4, v2, 24 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0xa21c32d7 /* vsrl.vi v5, v1, 24 */
    .word 0x2653c2d7 /* vand.vx v5, v5, x7 */
    .word 0x9642a257 /* vmul.vv v4, v4, v5 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x964c3257 /* vsll.vi v4, v4, 24 */
    .word 0x2a4201d7 /* vor.vv v3, v4, v4 */
    .word 0xa2283257 /* vsrl.vi v4, v2, 16 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0xa21832d7 /* vsrl.vi v5, v1, 16 */
    .word 0x2653c2d7 /* vand.vx v5, v5, x7 */
    .word 0x9642a257 /* vmul.vv v4, v4, v5 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x96483257 /* vsll.vi v4, v4, 16 */
    .word 0x2a3201d7 /* vor.vv v3, v3, v4 */
    .word 0xa2243257 /* vsrl.vi v4, v2, 8 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0xa21432d7 /* vsrl.vi v5, v1, 8 */
    .word 0x2653c2d7 /* vand.vx v5, v5, x7 */
    .word 0x9642a257 /* vmul.vv v4, v4, v5 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x96443257 /* vsll.vi v4, v4, 8 */
    .word 0x2a3201d7 /* vor.vv v3, v3, v4 */
    .word 0x2a210257 /* vor.vv v4, v2, v2 */
    .word 0x2643c257 /* vand.vx v4, v4, x7 */
    .word 0x2a1082d7 /* vor.vv v5, v1, v1 */
    .word 0x2653c2d7 /* vand.vx v5, v5, x7 */
    .word 0x9642a257 /* vmul.vv v4, v4, v5 */
    .word 0xa2443257 /* vsrl.vi v4, v4, 8 */
    .word 0x2a3201d7 /* vor.vv v3, v3, v4 */
    .word 0x0c028313 /* addi x6, x5, 192 */
    .word 0x020361a7 /* vse32.v v3, (x6) out-color */
    .insn i 0x73, 0, zero, zero, 0x305
