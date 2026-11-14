"""Wire-level regressions for the local printer-host fixture server."""

import argparse
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import sys
import threading
import unittest


parser = argparse.ArgumentParser(add_help=False)
parser.add_argument("--mock-path", type=Path, default=Path(__file__).with_name("phase6-printer-host-mock.py"))
options, unittest_arguments = parser.parse_known_args()
spec = importlib.util.spec_from_file_location("printer_host_mock", options.mock_path)
mock = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mock)


class PrinterHostMockTest(unittest.TestCase):
    def setUp(self):
        self.server = mock.ThreadingHTTPServer(("127.0.0.1", 0), mock.Handler)
        self.server.state = mock.State("octoprint")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=2)
        try:
            connection.request(method, path, body=body)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def test_no_content_start_response_has_no_entity_headers_or_body(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=2) as connection:
            connection.sendall(
                b"POST /api/files/local/test.gcode HTTP/1.1\r\nHost: localhost\r\n"
                b"Connection: close\r\nContent-Length: 2\r\n\r\n{}"
            )
            chunks = []
            while chunk := connection.recv(4096):
                chunks.append(chunk)
        headers, body = b"".join(chunks).split(b"\r\n\r\n", 1)
        self.assertEqual(b"204", headers.split(b"\r\n", 1)[0].split()[1])
        self.assertNotIn(b"content-length:", headers.lower())
        self.assertEqual(b"", body)

    def test_fixture_history_survives_per_test_request_reset(self):
        self.assertEqual(201, self.request("POST", "/api/files/local", "upload")[0])
        self.assertEqual(200, self.request("DELETE", "/__requests")[0])
        status, current = self.request("GET", "/__requests")
        self.assertEqual(200, status)
        self.assertEqual([], json.loads(current)["requests"])
        status, history = self.request("GET", "/__history")
        self.assertEqual(200, status)
        self.assertEqual(["/api/files/local"], [entry["path"] for entry in json.loads(history)["requests"]])

    def test_upload_responses_support_two_posts_on_one_http11_connection(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=2) as connection:
            for _ in range(2):
                connection.sendall(
                    b"POST /api/files/local HTTP/1.1\r\nHost: localhost\r\n"
                    b"Content-Length: 6\r\n\r\nupload"
                )
                response = http.client.HTTPResponse(connection)
                try:
                    response.begin()
                    self.assertEqual(11, response.version)
                    self.assertEqual(201, response.status)
                    self.assertTrue(json.loads(response.read())["done"])
                finally:
                    response.close()


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0], *unittest_arguments])
