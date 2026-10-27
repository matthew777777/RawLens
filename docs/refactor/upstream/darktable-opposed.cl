// Extracted from darktable 891e46cd57b64d67dbde183d5d3c0e430edd11d6 data/kernels/basic.cl
// Copyright darktable developers, GPL-3.0-or-later.
static float _calc_refavg(read_only image2d_t in,
                          global const unsigned char (*const xtrans)[6],
                          const unsigned int filters,
                          int row,
                          int col,
                          int maxrow,
                          int maxcol,
                          global const float *correction)
{
  float mean[3] = { 0.0f, 0.0f, 0.0f };
  float cnt[3]  = { 0.0f, 0.0f, 0.0f };

  for(int dy = max(0, row - 1); dy < min(maxrow - 1, row + 2); dy++)
  {
    for(int dx = max(0, col - 1); dx < min(maxcol - 1, col + 2); dx++)
    {
      const float val = fmax(0.0f, Areadsingle(in, dx, dy));
      const int c = fcol(dy, dx, filters, xtrans);
      mean[c] += val;
      cnt[c] += 1.0f;
    }
  }

  for(int c = 0; c < 3; c++)
    mean[c] = (cnt[c] > 0.0f) ? cbrt((correction[c] * mean[c]) / cnt[c]) : 0.0f;

  float croot_refavg[3] = { 0.5f * (mean[1] + mean[2]), 0.5f * (mean[0] + mean[2]), 0.5f * (mean[0] + mean[1])};
  int color = fcol(row, col, filters, xtrans);
  return fcube(croot_refavg[color]);
}

kernel void highlights_initmask(read_only image2d_t in,
                                global char *inmask,
                                const int msize,
                                const int mwidth,
                                const int mheight,
                                const int iwidth,
                                const int iheight,
                                const unsigned int filters,
                                global const unsigned char (*const xtrans)[6],
                                global const float *clips)
{
  const int mcol = get_global_id(0);
  const int mrow = get_global_id(1);

  if(mcol >= mwidth || mrow >= mheight)
    return;

  int mdx = mad24(mrow, mwidth, mcol);
  char mbuff[3] = { 0, 0, 0 };
  int irow = 3*mrow;
  int icol = 3*mcol;

  for(int y = irow; y < irow + 3; y++)
  {
    for(int x = icol; x < icol + 3; x++)
    {
      if(y < iheight && x < iwidth)
      {
        int color = fcol(y, x, filters, xtrans);
        float val = readsingle(in, x, y);
        mbuff[color] += (val >= clips[color]) ? 1 : 0;
      }
    }
  }

  for(int c = 0; c < 3; c++)
    inmask[c*msize + mdx] = mbuff[c] ? 1 : 0;
}

static char _mask_dilated(global char *in, const int w1)
{
  if(in[0])
    return 1;

  if(in[-w1-1] | in[-w1] | in[-w1+1] | in[-1] | in[1] | in[w1-1] | in[w1] | in[w1+1])
    return 1;

  const int w2 = 2*w1;
  const int w3 = 3*w1;
  return (in[-w3-2] | in[-w3-1] | in[-w3]   | in[-w3+1] | in[-w3+2] |
          in[-w2-3] | in[-w2-2] | in[-w2-1] | in[-w2]   | in[-w2+1] | in[-w2+2] | in[-w2+3] |
          in[-w1-3] | in[-w1-2] | in[-w1+2] | in[-w1+3] |
          in[-3]    | in[-2]    | in[2]     | in[3]     |
          in[w1-3]  | in[w1-2]  | in[w1+2]  | in[w1+3]  |
          in[w2-3]  | in[w2-2]  | in[w2-1]  | in[w2]    | in[w2+1]  | in[w2+2]  | in[w2+3] |
          in[w3-2]  | in[w3-1]  | in[w3]    | in[w3+1]  | in[w3+2]) ? 1 : 0;
}

kernel void highlights_dilatemask(global char *in,
                                  global char *out,
                                  const int msize,
                                  const int mwidth,
                                  const int mheight)
{
  const int col = get_global_id(0);
  const int row = get_global_id(1);

  if(col >= mwidth || row >= mheight)
    return;

  int mx = mad24(row, mwidth, col);
  bool safe = col >= 3 && row >= 3 && col < mwidth - 3 && row < mheight - 3;

  out[mx] =         safe ? _mask_dilated(in + mx, mwidth)           : in[mx];
  out[mx+msize] =   safe ? _mask_dilated(in + mx + msize, mwidth)   : in[mx + msize];
  out[mx+2*msize] = safe ? _mask_dilated(in + mx + 2*msize, mwidth) : in[mx + 2*msize];
}

kernel void highlights_chroma(read_only image2d_t in,
                              global char *mask,
                              global float *accu,
                              const int width,
                              const int height,
                              const int msize,
                              const int mwidth,
                              const unsigned int filters,
                              global const unsigned char (*const xtrans)[6],
                              global const float *clips,
                              global const float *correction)
{
  const int row = get_global_id(0);
  if(row >= height) return;

  float sum[4] = {0.0f, 0.0f, 0.0f, 0.0f};
  float cnt[4] = {0.0f, 0.0f, 0.0f, 0.0f};

  float clipped = 0.0f;
  for(int col = 0; col < width; col++)
  {
    const int idx = mad24(row, width, col);
    const int color = fcol(row, col, filters, xtrans);
    const float inval = Areadsingle(in, col, row);
    const int px = color * msize + mad24(row/3, mwidth, col/3);
    if(mask[px] && (inval > 0.2f*clips[color]) && (inval < clips[color]))
    {
      const float ref = _calc_refavg(in, xtrans, filters, row, col, height, width, correction);
      sum[color] += inval - ref;
      cnt[color] += 1.0f;
    }
    if(mask[px]) clipped += 1.0f;
  }

  for(int c = 0; c < 3; c++)
  {
    accu[row*8 + 2*c] = sum[c];
    accu[row*8 + 2*c +1] = cnt[c];
  }
  accu[row*8 + 6] = clipped;
}

kernel void highlights_opposed(read_only image2d_t in,
                               write_only image2d_t out,
                               const int owidth,
                               const int oheight,
                               const int iwidth,
                               const int iheight,
                               const int dx,
                               const int dy,
                               const unsigned int filters,
                               global const unsigned char (*const xtrans)[6],
                               global const float *clips,
                               global const float *chroma,
                               global const float *correction)
{
  const int x = get_global_id(0);
  const int y = get_global_id(1);
  if(x >= owidth || y >= oheight) return;

  int irow = y + dy;
  int icol = x + dx;
  float val = 0.0f;

  if((icol >= 0) && (icol < iwidth) && (irow >= 0) && (irow < iheight))
  {
    val = Areadsingle(in, icol, irow);
    const int color = fcol(irow, icol, filters, xtrans);
    if(val >= clips[color])
    {
      const float ref = _calc_refavg(in, xtrans, filters, irow, icol, iheight, iwidth, correction);
      val = fmax(val, ref + chroma[color]);
    }
  }
  write_imagef(out, (int2)(x, y), val);
}

