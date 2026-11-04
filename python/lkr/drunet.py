# SPDX-License-Identifier: GPL-3.0-or-later
"""DRUNet (DPIR, Zhang et al. TPAMI 2021) = KAIR UNetRes, the ResUNet of
USRNet [48] that LK-Reloaded uses as its prior. Re-declared here with the
KAIR state-dict layout so the public drunet_color.pth (MIT) loads strictly:
https://github.com/cszn/KAIR/releases/download/v1.0/drunet_color.pth
Input: (B,4,H,W) = RGB in [0,1] + constant sigma plane (sigma in [0,1]).
"""
import torch
import torch.nn as nn
import torch.nn.functional as F


class ResBlock(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.res = nn.Sequential(nn.Conv2d(c, c, 3, 1, 1, bias=False), nn.ReLU(True),
                                 nn.Conv2d(c, c, 3, 1, 1, bias=False))

    def forward(self, x):
        return x + self.res(x)


class UNetRes(nn.Module):
    def __init__(self, in_nc=4, out_nc=3, nc=(64, 128, 256, 512), nb=4):
        super().__init__()
        self.m_head = nn.Conv2d(in_nc, nc[0], 3, 1, 1, bias=False)

        def down(a, b):
            return nn.Sequential(*[ResBlock(a) for _ in range(nb)], nn.Conv2d(a, b, 2, 2, 0, bias=False))

        def up(a, b):
            return nn.Sequential(nn.ConvTranspose2d(a, b, 2, 2, 0, bias=False), *[ResBlock(b) for _ in range(nb)])

        self.m_down1 = down(nc[0], nc[1])
        self.m_down2 = down(nc[1], nc[2])
        self.m_down3 = down(nc[2], nc[3])
        self.m_body = nn.Sequential(*[ResBlock(nc[3]) for _ in range(nb)])
        self.m_up3 = up(nc[3], nc[2])
        self.m_up2 = up(nc[2], nc[1])
        self.m_up1 = up(nc[1], nc[0])
        self.m_tail = nn.Conv2d(nc[0], out_nc, 3, 1, 1, bias=False)

    def forward(self, x0):
        h, w = x0.shape[-2:]
        ph, pw = (-h) % 8, (-w) % 8
        x0 = F.pad(x0, (0, pw, 0, ph), mode='replicate')
        x1 = self.m_head(x0)
        x2 = self.m_down1(x1)
        x3 = self.m_down2(x2)
        x4 = self.m_down3(x3)
        x = self.m_body(x4)
        x = self.m_up3(x + x4)
        x = self.m_up2(x + x3)
        x = self.m_up1(x + x2)
        x = self.m_tail(x + x1)
        return x[..., :h, :w]


def load_drunet(path, device='cpu'):
    net = UNetRes()
    net.load_state_dict(torch.load(path, map_location='cpu', weights_only=True), strict=True)
    return net.eval().to(device)


class FFDNet(nn.Module):
    """FFDNet (Zhang et al. TIP 2018), KAIR layout: pixel-unshuffle x2, noise
    map concatenated, 12 x (conv3x3 96ch + ReLU), pixel-shuffle back.
    Weights: https://github.com/cszn/KAIR/releases/download/v1.0/ffdnet_color.pth
    Takes the same (B,4,H,W) input as UNetRes (RGB + sigma plane)."""

    def __init__(self, in_nc=3, out_nc=3, nc=96, nb=12):
        super().__init__()
        L = [nn.Conv2d(in_nc * 4 + 1, nc, 3, 1, 1), nn.ReLU(True)]
        for _ in range(nb - 2):
            L += [nn.Conv2d(nc, nc, 3, 1, 1), nn.ReLU(True)]
        L += [nn.Conv2d(nc, out_nc * 4, 3, 1, 1)]
        self.model = nn.Sequential(*L)

    def forward(self, inp):
        x, sig = inp[:, :3], inp[:, 3:4]
        h, w = x.shape[-2:]
        x = F.pad(x, (0, w % 2, 0, h % 2), mode='replicate')
        x = F.pixel_unshuffle(x, 2)
        m = F.avg_pool2d(F.pad(sig, (0, w % 2, 0, h % 2), mode='replicate'), 2)
        x = F.pixel_shuffle(self.model(torch.cat([x, m], 1)), 2)
        return x[..., :h, :w]


def load_ffdnet(path, device='cpu'):
    net = FFDNet()
    net.load_state_dict(torch.load(path, map_location='cpu', weights_only=True), strict=True)
    return net.eval().to(device)
