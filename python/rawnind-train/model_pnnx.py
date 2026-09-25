# pnnx model stat
# model inputshape = [1,5,528,528]f32
# FLOPS = 73.01G
# memory OPS = 412.421M

import os
import numpy as np
import tempfile, zipfile
import torch
import torch.nn as nn
import torch.nn.functional as F
try:
    import torchvision
    import torchaudio
except:
    pass

class Model(nn.Module):
    def __init__(self):
        super(Model, self).__init__()

        self.conv2d_0 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=5, kernel_size=(3,3), out_channels=32, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_1 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=32, kernel_size=(3,3), out_channels=32, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_2 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=32, kernel_size=(3,3), out_channels=64, padding=(1,1), padding_mode='zeros', stride=(2,2))
        self.conv2d_3 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=64, kernel_size=(3,3), out_channels=64, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_4 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=64, kernel_size=(3,3), out_channels=64, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_5 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=64, kernel_size=(3,3), out_channels=128, padding=(1,1), padding_mode='zeros', stride=(2,2))
        self.conv2d_6 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=128, kernel_size=(3,3), out_channels=128, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_7 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=128, kernel_size=(3,3), out_channels=128, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_8 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=192, kernel_size=(3,3), out_channels=64, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_9 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=64, kernel_size=(3,3), out_channels=64, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_10 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=96, kernel_size=(3,3), out_channels=32, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_11 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=32, kernel_size=(3,3), out_channels=32, padding=(1,1), padding_mode='zeros', stride=(1,1))
        self.conv2d_12 = nn.Conv2d(bias=True, dilation=(1,1), groups=1, in_channels=32, kernel_size=(1,1), out_channels=4, padding=(0,0), padding_mode='zeros', stride=(1,1))

        archive = zipfile.ZipFile('model.pnnx.bin', 'r')
        self.conv2d_0.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_0.bias', (32), 'float32')
        self.conv2d_0.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_0.weight', (32,5,3,3), 'float32')
        self.conv2d_1.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_1.bias', (32), 'float32')
        self.conv2d_1.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_1.weight', (32,32,3,3), 'float32')
        self.conv2d_2.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_2.bias', (64), 'float32')
        self.conv2d_2.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_2.weight', (64,32,3,3), 'float32')
        self.conv2d_3.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_3.bias', (64), 'float32')
        self.conv2d_3.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_3.weight', (64,64,3,3), 'float32')
        self.conv2d_4.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_4.bias', (64), 'float32')
        self.conv2d_4.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_4.weight', (64,64,3,3), 'float32')
        self.conv2d_5.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_5.bias', (128), 'float32')
        self.conv2d_5.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_5.weight', (128,64,3,3), 'float32')
        self.conv2d_6.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_6.bias', (128), 'float32')
        self.conv2d_6.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_6.weight', (128,128,3,3), 'float32')
        self.conv2d_7.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_7.bias', (128), 'float32')
        self.conv2d_7.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_7.weight', (128,128,3,3), 'float32')
        self.conv2d_8.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_8.bias', (64), 'float32')
        self.conv2d_8.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_8.weight', (64,192,3,3), 'float32')
        self.conv2d_9.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_9.bias', (64), 'float32')
        self.conv2d_9.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_9.weight', (64,64,3,3), 'float32')
        self.conv2d_10.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_10.bias', (32), 'float32')
        self.conv2d_10.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_10.weight', (32,96,3,3), 'float32')
        self.conv2d_11.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_11.bias', (32), 'float32')
        self.conv2d_11.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_11.weight', (32,32,3,3), 'float32')
        self.conv2d_12.bias = self.load_pnnx_bin_as_parameter(archive, 'conv2d_12.bias', (4), 'float32')
        self.conv2d_12.weight = self.load_pnnx_bin_as_parameter(archive, 'conv2d_12.weight', (4,32,1,1), 'float32')
        archive.close()

    def load_pnnx_bin_as_parameter(self, archive, key, shape, dtype, requires_grad=True):
        return nn.Parameter(self.load_pnnx_bin_as_tensor(archive, key, shape, dtype), requires_grad)

    def load_pnnx_bin_as_tensor(self, archive, key, shape, dtype):
        fd, tmppath = tempfile.mkstemp()
        with os.fdopen(fd, 'wb') as tmpf, archive.open(key) as keyfile:
            tmpf.write(keyfile.read())
        m = np.memmap(tmppath, dtype=dtype, mode='r', shape=shape).copy()
        os.remove(tmppath)
        return torch.from_numpy(m)

    def forward(self, v_0):
        v_1 = v_0[:,:4]
        v_2 = self.conv2d_0(v_0)
        v_3 = F.leaky_relu(v_2, negative_slope=0.2)
        v_4 = self.conv2d_1(v_3)
        v_5 = F.leaky_relu(v_4, negative_slope=0.2)
        v_6 = self.conv2d_2(v_5)
        v_7 = F.leaky_relu(v_6, negative_slope=0.2)
        v_8 = self.conv2d_3(v_7)
        v_9 = F.leaky_relu(v_8, negative_slope=0.2)
        v_10 = self.conv2d_4(v_9)
        v_11 = F.leaky_relu(v_10, negative_slope=0.2)
        v_12 = self.conv2d_5(v_11)
        v_13 = F.leaky_relu(v_12, negative_slope=0.2)
        v_14 = self.conv2d_6(v_13)
        v_15 = F.leaky_relu(v_14, negative_slope=0.2)
        v_16 = self.conv2d_7(v_15)
        v_17 = F.leaky_relu(v_16, negative_slope=0.2)
        v_18 = F.interpolate(v_17, mode='nearest', recompute_scale_factor=False, scale_factor=(2.0,2.0))
        v_19 = torch.cat((v_18, v_11), dim=1)
        v_20 = self.conv2d_8(v_19)
        v_21 = F.leaky_relu(v_20, negative_slope=0.2)
        v_22 = self.conv2d_9(v_21)
        v_23 = F.leaky_relu(v_22, negative_slope=0.2)
        v_24 = F.interpolate(v_23, mode='nearest', recompute_scale_factor=False, scale_factor=(2.0,2.0))
        v_25 = torch.cat((v_24, v_5), dim=1)
        v_26 = self.conv2d_10(v_25)
        v_27 = F.leaky_relu(v_26, negative_slope=0.2)
        v_28 = self.conv2d_11(v_27)
        v_29 = F.leaky_relu(v_28, negative_slope=0.2)
        v_30 = self.conv2d_12(v_29)
        v_31 = (v_1 + v_30)
        return v_31

def export_torchscript():
    net = Model()
    net.float()
    net.eval()

    torch.manual_seed(0)
    v_0 = torch.rand(1, 5, 528, 528, dtype=torch.float)

    mod = torch.jit.trace(net, v_0)
    mod.save("model_pnnx.py.pt")

def export_onnx():
    net = Model()
    net.float()
    net.eval()

    torch.manual_seed(0)
    v_0 = torch.rand(1, 5, 528, 528, dtype=torch.float)

    torch.onnx.export(net, v_0, "model_pnnx.py.onnx", export_params=True, operator_export_type=torch.onnx.OperatorExportTypes.ONNX_ATEN_FALLBACK, opset_version=13, input_names=['in0'], output_names=['out0'])

def export_pnnx():
    net = Model()
    net.float()
    net.eval()

    torch.manual_seed(0)
    v_0 = torch.rand(1, 5, 528, 528, dtype=torch.float)

    import pnnx
    pnnx.export(net, "model_pnnx.py.pt", v_0)

def export_ncnn():
    export_pnnx()

@torch.no_grad()
def test_inference():
    net = Model()
    net.float()
    net.eval()

    torch.manual_seed(0)
    v_0 = torch.rand(1, 5, 528, 528, dtype=torch.float)

    return net(v_0)

if __name__ == "__main__":
    print(test_inference())
