import enum
import logging
from threading import Timer

import llp
import queue
import struct
import threading
from threading import *


class SWPType(enum.IntEnum):
    DATA = ord('D')
    ACK = ord('A')


class SWPPacket:
    _PACK_FORMAT = '!BI'
    _HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
    MAX_DATA_SIZE = 1400  # Leaves plenty of space for IP + UDP + SWP header

    def __init__(self, type, seq_num, data=b''):
        self._type = type
        self._seq_num = seq_num
        self._data = data

    @property
    def type(self):
        return self._type

    @property
    def seq_num(self):
        return self._seq_num

    @property
    def data(self):
        return self._data

    def to_bytes(self):
        header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value,
                             self._seq_num)
        return header + self._data

    @classmethod
    def from_bytes(cls, raw):
        header = struct.unpack(SWPPacket._PACK_FORMAT,
                               raw[:SWPPacket._HEADER_SIZE])
        type = SWPType(header[0])
        seq_num = header[1]
        data = raw[SWPPacket._HEADER_SIZE:]
        return SWPPacket(type, seq_num, data)

    def __str__(self):
        return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))


class SWPSender:
    _SEND_WINDOW_SIZE = 5

    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                                             loss_probability=loss_probability)

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # TODO: Add additional state variables
        self._TIMEOUT = 1
        self._windows = Semaphore(value=self._SEND_WINDOW_SIZE)
        self._seq_num = -1
        self._sendBuf = {}  # [None] * self._SEND_WINDOW_SIZE
        for i in range(self._SEND_WINDOW_SIZE):
            self._sendBuf[i] = None
        self._last_Writen = 0
        self._last_Send = 0
        self.lock = threading.Lock()
        self.timers_dict = {}

    def seqNum(self):
        return self._seqNum

    def seqNum(self, val):
        self._seqNum = val

    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i + SWPPacket.MAX_DATA_SIZE])

    def _send(self, data):
        # TODO
        self._windows.acquire()
        self._seq_num += 1

        # logging.debug("send SWPSender._windows --  %s" % self._windows)
        packet = SWPPacket(SWPType.DATA, self._seq_num, data)

        self._sendBuf[self._seq_num % self._SEND_WINDOW_SIZE] = packet
        # self._last_Writen = self._seq_num
        # logging.debug("send Buffer --  %s" % self._sendBuf)

        self._llp_endpoint.send(packet.to_bytes())
        logging.debug("Sent %s" % packet)

        time_out_period = threading.Timer(self._TIMEOUT, self._retransmit, [self._seq_num])
        time_out_period.start()

        self.timers_dict[self._seq_num] = time_out_period

        return

    def _retransmit(self, seq_num):
        # TODO
        self.lock.acquire()
        if seq_num in self._sendBuf.keys():
            self._llp_endpoint.send(self._sendBuf[seq_num % self._SEND_WINDOW_SIZE].to_bytes())
            time_out_mach = threading.Timer(self._TIMEOUT, self._retransmit, [seq_num])
            time_out_mach.start()
            self.timers_dict[seq_num] = time_out_mach
            logging.debug("Retransmit: %s" % self._sendBuf[seq_num % self._SEND_WINDOW_SIZE])

        self.lock.release()
        return

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)

            # TODO
            # time_out_mach = threading.Timer(self._TIMEOUT, self._retransmit, args=(packet.seq_num,))
            # if packet.type != SWPType.ACK:
            #     continue
            if packet.type == SWPType.ACK:
                #     loop through  0 to seq_num
                #     check if that i in timers, if yes -> cancel, self._windows
                for i in range(0, packet.seq_num + 1):
                    self.lock.acquire()
                    if i in self.timers_dict:
                        # logging.debug("Received   self.timers_dict: %s" % self.timers_dict)
                        self.timers_dict[i].cancel()
                        self.timers_dict.pop(i)
                        # self._sendBuf.pop(i)
                        # logging.debug("Received   self.timers_dict 22 : %s" % self.timers_dict)
                        self._sendBuf[packet.seq_num % self._SEND_WINDOW_SIZE] = None
                        self._windows.release()
                    self.lock.release()

                # self._sendBuf[packet.seq_num % SWPSender._SEND_WINDOW_SIZE] = None
        return


class SWPReceiver:
    _RECV_WINDOW_SIZE = 5

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address,
                                             loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # TODO: Add additional state variables
        self._received_Buffer = {}# [None] * self._RECV_WINDOW_SIZE
        for i in range(self._RECV_WINDOW_SIZE):
            self._received_Buffer[i] = None
        self._highest_seq = 0
        self._ack_highest = 0
        self._find_high = 0

    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        while True:
            raw = self._llp_endpoint.recv()
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)
            # TODO
            if packet.type == SWPType.ACK:
                continue
            # if packet seq num <= find_high:
            #     create ack packet, send and  continue
            if packet.seq_num <= self._find_high:
                self._find_high += 1
                tempPacket = SWPPacket(SWPType.ACK, self._find_high)
                self._llp_endpoint.send(tempPacket.to_bytes())
                continue

            if packet.type != SWPType.ACK:
                self._received_Buffer[packet.seq_num % SWPReceiver._RECV_WINDOW_SIZE] = packet
                for chunk in self._received_Buffer.keys():
                    if self._received_Buffer[chunk % self._RECV_WINDOW_SIZE]:
                        self._ready_data.put(chunk)
                        self._received_Buffer[self._highest_seq % SWPReceiver._RECV_WINDOW_SIZE] = None
                        self._highest_seq += 1
                    else:
                        # hole
                        tempPacket2 = SWPPacket(SWPType.ACK, self._highest_seq)
                        self._llp_endpoint.send(tempPacket2.to_bytes())
                        break
                # break
        return