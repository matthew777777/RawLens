#!/usr/bin/env python3
"""Regression for Linear DNG archive compatibility, optionally using Rawler."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import numpy as np
import tifffile
from lkr_render_validation import wrap_linear


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source',type=Path,required=True)
    ap.add_argument('--decoder',type=Path)
    a=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='lkr-dng-regression-') as tmp:
        root=Path(tmp);pixels=(np.arange(8*8*3).reshape(8,8,3)*313).astype('u2')
        linear=root/'linear.tif';good=root/'good.dng';bad=root/'legacy-scalar-black.dng'
        tifffile.imwrite(linear,pixels,photometric='rgb',metadata=None)
        wrap_linear(a.source,linear,good)
        omitted={256,257,258,259,262,273,277,278,279,284,305,339}
        tags=[]
        with tifffile.TiffFile(good) as t:
            page=t.pages[0]
            assert page.samplesperpixel==3
            assert page.tags[50714].count==3 and page.tags[50717].count==3
            assert np.array_equal(page.asarray(),pixels)
            for tag in page.tags.values():
                if tag.code in omitted:continue
                if tag.code==50714:tags.append((50714,'2I',1,(0,1),False))
                else:tags.append((tag.code,tag.dtype,tag.count,tag.value,False))
        tifffile.imwrite(bad,pixels,photometric=34892,planarconfig='contig',metadata=None,extratags=tags)
        if a.decoder:
            accepted=subprocess.run([str(a.decoder),str(good)],capture_output=True,text=True)
            rejected=subprocess.run([str(a.decoder),str(bad)],capture_output=True,text=True)
            assert accepted.returncode==0,accepted.stdout+accepted.stderr
            assert rejected.returncode!=0,'legacy scalar BlackLevel unexpectedly accepted'
            print('PASS: RapidRAW decoder accepts RGB BlackLevel[3] and rejects legacy BlackLevel[1]')
        print('PASS: RGB level counts, native dimensions, and unchanged uint16 image pixels')


if __name__=='__main__':main()
