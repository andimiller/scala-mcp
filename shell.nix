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
    # TLS for fs2.io.net / http4s-ember-client on Native (used by example-harness).
    s2n-tls

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
