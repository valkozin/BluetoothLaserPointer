import socket
import pyautogui

# To move the mouse, we use pyautogui. 
# Install it first: pip3 install pyautogui

# Setup socket server
HOST = 'localhost'
PORT = 8080

# Disable FailSafe if you want (PyAutoGUI raises exception if mouse is in top-left)
pyautogui.FAILSAFE = False
# CRITICAL: Eliminate default 0.1s delay between moves for instant response
pyautogui.PAUSE = 0

def start_server():
    screen_width, screen_height = pyautogui.size()
    
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((HOST, PORT))
        s.listen()
        print(f"Server listening on {HOST}:{PORT}...")
        print("Wait for 'adb reverse tcp:8080 tcp:8080' and client connection.")
        
        while True:
            conn, addr = s.accept()
            with conn:
                print(f"Connected by {addr}")
                buffer = ""
                while True:
                    try:
                        data = conn.recv(1024).decode('utf-8')
                        if not data:
                            print("Disconnected.")
                            break
                        
                        buffer += data
                        while "\n" in buffer:
                            line, buffer = buffer.split("\n", 1)
                            line = line.strip()
                            
                            if line == "CENTER":
                                pyautogui.moveTo(screen_width // 2, screen_height // 2)
                            elif line == "LCLICK":
                                pyautogui.click(button='left')
                            elif line == "RCLICK":
                                pyautogui.click(button='right')
                            elif "," in line:
                                try:
                                    dx, dy = map(int, line.split(","))
                                    pyautogui.moveRel(dx, dy)
                                except ValueError:
                                    pass
                    except Exception as e:
                        print(f"Error: {e}")
                        break

if __name__ == "__main__":
    start_server()
