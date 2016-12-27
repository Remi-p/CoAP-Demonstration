import time
from coapthon import defines
from coapthon.client.coap import CoAP
from coapthon.client.helperclient import HelperClient
from coapthon.messages.message import Message
from coapthon.messages.request import Request
from coapthon.utils import parse_uri

import pprint
import socket

from coapthon.resources.resource import Resource

__author__ = 'Remi Perrot'
__version__ = "0.1"

class LightResource(Resource):
    
    client = None
    path = None
    
    def __init__(self, name="LightResource", coap_server=None):
        super(LightResource, self).__init__(name, coap_server, visible=True,
                                            observable=True, allow_children=True)
        self.payload = "n/a"
        self.resource_type = "rt2"
        self.content_type = "text/plain"
        self.interface_type = "if2"
        
        path = "coap://192.168.0.3/light"
        host, port, self.path = parse_uri(path)
        host = socket.gethostbyname(host)
        self.client = HelperClient(server=(host, port))

    def render_GET(self, request):
        
        response = self.client.get(self.path)
        print response.pretty_print()
        self.payload = response.payload
        return self

    def render_DELETE(self, request):
        return True
