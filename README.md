## Flow Control and DNS

### Overview

This project is a part of Lab 5 for CS640 Fall 2022 at the University of Wisconsin - Madison. The lab consists of two parts:

1. **Flow Control**: Implement a Python-based data sender and receiver using the sliding window algorithm.
2. **Simple DNS Server**: Write a simple Java-based DNS server that performs recursive DNS resolutions and appends a special annotation if an IP address belongs to an Amazon EC2 region.

### Learning Objectives

- Explain how the sliding window algorithm facilitates flow control.
- Explain how the domain name system (DNS) works.

### Project Structure

- **fc/**: Contains the code for Part 1 (Flow Control).
- **src/**: Contains the code for Part 2 (Simple DNS Server).
- **Makefile**: Used to compile and run the Java-based DNS server.
- **README.md**: This file.

### Part 1: Flow Control

#### Background

You will implement a simple sliding window protocol (SWP) in Python that transmits data in only one direction and acknowledgements in the reverse direction. The SWP uses the `SWPPacket` class to represent packets and interacts with a lower layer protocol (LLP) using the `LLPEndpoint` class.

#### Implementation

1. **SWPSender**

   - **_send**: Buffers and sends a data packet within the send window.
   - **_retransmit**: Retransmits a data packet if not acknowledged within a predetermined timeout.
   - **_recv**: Receives ACK packets and processes acknowledgements.

2. **SWPReceiver**

   - **_recv**: Receives data packets, buffers them, sends cumulative ACKs, and delivers data to the application.

#### Testing

To test the sender and receiver:
1. Run the test server:
   ```
   ./fc/server.py -p PORT
   ```
2. In another terminal, start the client:
   ```
   ./fc/client.py -p PORT -h 127.0.0.1
   ```
3. Type something into the client and check if it appears on the server.

To test retransmission, include the `-l PROBABILITY` argument when starting the client or server to simulate packet loss.

### Part 2: Simple DNS Server

#### Background

You will implement a simple DNS server in Java that accepts queries, performs recursive resolutions, and appends TXT records for Amazon EC2 regions.

#### Command Line Arguments

Your DNS server should be invoked as follows:
```
java edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>
```
- `-r <root server ip>`: Specifies the IP address of a root DNS server.
- `-e <ec2 csv>`: Specifies the path to a CSV file containing IP address ranges for Amazon EC2 regions.

#### Implementation

1. **Receiving Queries**
   - Listen for UDP packets on port 8053.
   - Parse DNS queries using the `deserialize` method in the `DNS` class.

2. **Handling Queries**
   - Recursively resolve A, AAAA, CNAME, and NS queries.
   - Append a TXT record for IP addresses in EC2 regions.

#### Testing

Use `dig` to test your DNS server:
```
dig +norecurse @localhost -p 8053 A www.example.com
```
Check that the responses match those from a standard DNS server. Use `tcpdump` to capture and analyze DNS packets.

### Makefile

The Makefile enables compilation and execution of the DNS server:
- **make**: Compiles the Java code.
- **make run**: Runs the DNS server.
- **make clean**: Removes `.class` files.
