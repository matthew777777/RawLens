// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the actual shared production engine, including persistent frame
// reuse.
#include "photo_rcd_engine.h"
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <vector>
template <class T> std::vector<T> load(const std::string &path) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f)
    throw std::runtime_error("input open");
  size_t n = size_t(f.tellg());
  if (n % sizeof(T))
    throw std::runtime_error("input alignment");
  std::vector<T> a(n / sizeof(T));
  f.seekg(0);
  f.read(reinterpret_cast<char *>(a.data()), n);
  if (!f)
    throw std::runtime_error("input read");
  return a;
}
int main(int argc, char **argv) {
  try {
    if (argc != 3 && argc != 4)
      throw std::runtime_error("usage: engine-host shader.spv jobs.txt");
    bool baked = argc == 4 && std::string(argv[3]) == "candidate-baked";
    std::vector<std::vector<uint32_t>> stages;
    if (baked) {
      stages.resize(8);
      std::string path = argv[1];
      path = path.substr(0, path.find_last_of('/'));
      for (int pass : {0, 1, 2, 3, 4, 6, 7})
        stages[pass] =
            load<uint32_t>(path + "/stage-" + std::to_string(pass) + ".spv");
    }
    photo_rcd::Engine engine(
        load<uint32_t>(argv[1]), argc == 4,
        (baked || (argc == 4 && std::string(argv[3]) == "candidate-cached")),
        false, argc == 4 && std::string(argv[3]) == "candidate-images",
        argc == 4 && std::string(argv[3]).find("candidate") == 0, stages,
        argc == 4 && std::string(argv[3]) == "candidate-packed");
    std::cout << engine.deviceName() << '\n';
    std::ifstream jobs(argv[2]);
    std::string line;
    int count = 0;
    while (std::getline(jobs, line)) {
      std::istringstream job(line);
      int w, h;
      uint32_t phase;
      std::string source, destination;
      if (!(job >> w >> h >> phase >> source >> destination))
        throw std::runtime_error("invalid job");
      auto cfa = load<float>(source);
      std::vector<float> rgba(size_t(w) * h * 4);
      engine.render(w, h, phase, cfa.data(), cfa.size(), rgba.data(),
                    rgba.size());
      std::ofstream out(destination, std::ios::binary);
      out.write(reinterpret_cast<char *>(rgba.data()), rgba.size() * 4);
      if (!out)
        throw std::runtime_error("output write");
      ++count;
    }
    std::vector<float> cfa(19 * 19, .25f), rgba(19 * 19 * 4);
    for (uint32_t bad : {0u, 255u, 132u}) {
      bool rejected = false;
      try {
        engine.render(19, 19, bad, cfa.data(), cfa.size(), rgba.data(),
                      rgba.size());
      } catch (const std::invalid_argument &) {
        rejected = true;
      }
      if (!rejected)
        throw std::runtime_error("invalid CFA accepted");
    }
    // An invalid request must not poison the engine's following frame.
    engine.render(19, 19, 148, cfa.data(), cfa.size(), rgba.data(),
                  rgba.size());
    std::cout << "completed " << count
              << " frames and invalid-phase recovery\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 2;
  }
}
