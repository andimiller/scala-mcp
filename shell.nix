{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    # Scala Native dependencies
    clang
    llvm
    zlib
    libunwind
    boehmgc
    re2

    # Build tools
    sbt

    # Optional: useful for development
    git
  ];

  shellHook = ''
    echo "Scala Native development environment"
    echo "clang version: $(clang --version | head -n1)"
    echo ""
    echo "Ready to build Scala Native projects!"
  '';
}
