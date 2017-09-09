# Amelia Example Projects

These examples are intended to demonstrate features of the Amelia DSL. Keep in mind that most of them assume the remote hosts to run on Ubuntu (e.g., apt-get as package manager). However, these assumptions are made only to keep them as simple as possible; updating the examples to support other platforms should not be complicated.

## List of Examples

- [Base library](base) defines both Java files and Amelia subsystems that are reused within some example projects.
- [Eclipse plugin project](eclipse-plugin-project) shows the basic configuration to use Amelia within an Eclipse plugin project.
- [Docker helloworld](docker-hello-world) installs Docker Community Edition and runs the hello-world container.
- [FraSCAti helloworld-rmi](frascati-helloworld-rmi) runs FraSCAti 1.4 on docker and compiles and executes the helloworld-rmi project from the FraSCAti distribution.