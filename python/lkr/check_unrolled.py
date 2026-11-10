# SPDX-License-Identifier: GPL-3.0-or-later
"""Check gradient flow through motion, prior and explicit HQS schedules."""
import torch
from lkr_torch import Ops, unrolled

torch.manual_seed(2026)
torch.set_num_threads(2)
p=torch.tensor([[1.,0,0,0,1,0],[1.,0,.21,0,1,-.17]],requires_grad=True)
z=torch.rand(3,32,40)
y=Ops(16,20,2,'cpu').setup(p).fwd(z).detach()+torch.randn(2,16,20)*.01
prior=torch.nn.Conv2d(3,3,3,padding=1)
mu=torch.tensor([.1,1.],requires_grad=True)
eta=torch.tensor([.1,.1],requires_grad=True)
x,_,_=unrolled(y,p,z,prior,2,mu,eta)
x.square().mean().backward()
for name,g in [('motion',p.grad),('mu',mu.grad),('eta',eta.grad),('prior',prior.weight.grad)]:
    assert g is not None and torch.isfinite(g).all() and g.abs().max()>0,name
    print(name,'gradient',float(g.abs().max()))
print('PASS differentiable unrolling (not trained-model parity)')
