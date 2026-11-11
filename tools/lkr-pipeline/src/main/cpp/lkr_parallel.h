// SPDX-License-Identifier: Apache-2.0
#pragma once
// Minimal row-range parallel-for for the CPU stages around the GPU work
// (prior GAT/pack/unpack, look finishing). Deterministic: each index range is
// written by exactly one thread, no reductions.
#include <algorithm>
#include <thread>
#include <vector>

namespace lkr {

template <class F>
void ParallelFor(int n, F fn, int max_threads = 4) {
  const int hc = int(std::thread::hardware_concurrency());
  const int nt = std::max(1, std::min({max_threads, hc > 0 ? hc : 4, n / 16 > 0 ? n / 16 : 1}));
  if (nt == 1) { fn(0, n); return; }
  std::vector<std::thread> th;
  th.reserve(nt - 1);
  const int step = (n + nt - 1) / nt;
  for (int t = 1; t < nt; ++t) {
    const int b = t * step, e = std::min(n, b + step);
    if (b < e) th.emplace_back([=, &fn] { fn(b, e); });
  }
  fn(0, std::min(n, step));
  for (auto& x : th) x.join();
}

}  // namespace lkr
