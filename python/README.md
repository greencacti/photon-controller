# Building Python Code

## Pre-requisites

### Devbox
Photon devbox contains everything needed to develop, test and build python
agent.

See [here](../devbox-photon) for details of how to use Photon devbox.

### OS X

#### Thrift
Thrift 0.9.1 is required. However, Homebrew recently updates thrift to a
newer version. Follow the following steps to install thrift:

```bash
cd $( brew --prefix )
git checkout -b thrift-0.9.1 a61e5573f1e2bf06698038802b653f4a005e0743
# If the previous command failed with the following error:
#   fatal: reference is not a tree: a61e5573f1e2bf06698038802b653f4a005e0743
# It might mean the homebrew is a shallow clone. Run the following command
# and try again:
# git fetch --unshallow
brew install thrift
git checkout master
git branch -d thrift-0.9.1
```

#### Python
System python can be used locally to build and test python code. Make sure you
have both python 2.6 and 2.7 installed.

virtualenv 1.9.1 is also required to be installed. Newer or older version could
cause agent incompatibility issue while running in ESX server.

#### Vibauthor
Vibauthor is the tool to package all the python code into a bundle that can be
installed on ESX server. Unfortunately, the tool isn't available in OS X. Thus,
to build agent, you have to find a Linux box.

### Linux

#### Thrift
Check the official [document](https://thrift.apache.org/docs/install/) to
install thrift 0.9.1.

#### Python
System python can be used locally to build and test python code. Make sure you
have both python 2.6 and 2.7 installed.

virtualenv 1.9.1 is also required to be installed. Newer or older version could
cause agent incompatibility issue while running in ESX server.

See the ESX section of the document for details on using the esx hypervisor.

#### Vibauthor
Follow the instruction here:
[https://labs.vmware.com/flings/vib-author](https://labs.vmware.com/flings/vib-author).

Only RPM is available, so all the DEB based OSes are not supported.

## Development environment
We use make for building the python components. There is a single top level
Makefile which calls out to per-package Makefiles in src/\*/Makefile.

The python/Makefile defines aggregate targets that will be run on all the
src packages. You can also run the targets on the individual package.

To setup development environment:

```bash
make develop
```

Activate virtualenv, so you can run nosetests and other commands without
specifying the absolute path. The develop directory is automatically created
when you run `make develop` or `make test`.

```bash
. develop/bin/activate
```

To run unit tests:

```bash
make test
```

```bash
make test # Runs all of the tests
cd src/agent
make test # Runs just the agent tests
```

To deactivate virtualenv:

```bash
# only works if you used the step above to activate
deactivate
```

To clean up the build artifacts:

```bash
make clean
```

## Test options

The following examples assume virtualenv has been activated.

### Run specific tests

Test a single module:
```bash
nosetests src/host/host/tests/unit/test_host_handler.py
```

Or use the package name:
```bash
nosetests host.tests.unit.test_host_handler
```

Run a specific test within a module:
```bash
nosetests host.tests.unit.test_host_handler:HostHandlerTestCase.test_get_resources
```

### Integration tests

To run integration tests:

Integration test only runs while ZOOKEEPER\_PATH environment variable is
provided and points to an extracted Zookeeper installation:

```bash
ZOOKEEPER_PATH=/usr/local/Cellar/zookeeper/3.4.5/libexec make test INTEGRATION=1
```

Zookeeper tests will only run if ZOOKEEPER\_PATH environment variable is
provided and points to an extracted Zookeeper installation:

```bash
ZOOKEEPER_PATH=/usr/local/Cellar/zookeeper/3.4.5/libexec make test
```

### Run agent stress test

There is a stress test that creates multiple VMs concurrently. To run it, do:

```bash
nosetests -s --tc agent_remote_stress_test.host:$ESX_IP \
    agent.tests.stress.test_remote_stress_agent:TestRemoteStressAgent
```

or, using a host file with 5 hosts ips (one per line), start 2\*5 concurrent
threads creating a total of 2\*5\*7 VMs

```bash
nosetests -s --tc agent_remote_stress_test.threads_per_host:2 \
--tc agent_remote_stress_test.vms_per_thread:7 \
--tc agent_remote_stress_test.hostfile:hostfile \
agent.tests.stress.test_remote_stress_agent:TestRemoteStressAgent
```

### Running the agent locally

To build and run the agent server locally (it will hang until you ctrl-c):

```bash
# photon-controller-agent is located in develop/bin which is added to the PATH by
# virtualenv activation
photon-controller-agent
```

## Building the agent vib for ESX

To build and deploy a VIB:
```bash
make vib REMOTE_SERVER=host IMAGES_DIR=$PWD/develop/images
```

To build and deploy a VIB for debug purposes (i.e. with tests and .py files)
```bash
make vib REMOTE_SERVER=host IMAGES_DIR=$PWD/develop/images DEBUG=1
```

ZOOKEEPER\_PATH environment variable must be set in order to run
remote host tests:

```bash
ZOOKEEPER_PATH=/usr/local/Cellar/zookeeper/3.4.5/libexec make test INTEGRATION=1 \
DATASTORES=datastore1 REMOTE_ISO="[datastore1] path/to/test.iso" REMOTE_SERVER=host1
```

