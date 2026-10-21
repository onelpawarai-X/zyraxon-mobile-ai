import tkinter as tk
from tkinter import ttk, messagebox
import datetime

class MoonApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Moon Phase Tracker")
        self.geometry("420x320")
        self.configure(bg="#0b1020")
        self.resizable(False, False)

        # Title
        tk.Label(self, text="🌙 Moon Phase Tracker", font=("Segoe UI", 16, "bold"),
                 bg="#0b1020", fg="#e0e0e0").pack(pady=12)

        # Date entry
        frame = tk.Frame(self, bg="#0b1020")
        frame.pack(pady=6)
        tk.Label(frame, text="Date (YYYY-MM-DD):", bg="#0b1020", fg="#9aa0a6").grid(row=0, column=0, padx=6)
        self.date_var = tk.StringVar(value=datetime.date.today().isoformat())
        ttk.Entry(frame, textvariable=self.date_var, width=14).grid(row=0, column=1)

        # Button
        ttk.Button(self, text="Show Phase", command=self.show_phase).pack(pady=10)

        # Result
        self.result = tk.Label(self, text="", font=("Segoe UI", 13), bg="#0b1020", fg="#f5f5f5", justify="center")
        self.result.pack(pady=12)

        # Info
        tk.Label(self, text="Approximate phase based on 29.53-day synodic cycle.",
                 bg="#0b1020", fg="#666", font=("Segoe UI", 9)).pack(side="bottom", pady=8)

    def show_phase(self):
        try:
            d = datetime.date.fromisoformat(self.date_var.get())
        except ValueError:
            messagebox.showerror("Invalid date", "Use YYYY-MM-DD format.")
            return
        # Known new moon: 2000-01-06
        ref = datetime.date(2000, 1, 6)
        days = (d - ref).days
        phase = (days % 29.530588) / 29.530588
        if phase < 0.0625 or phase >= 0.9375:
            name = "🌑 New Moon"
        elif phase < 0.1875:
            name = "🌒 Waxing Crescent"
        elif phase < 0.3125:
            name = "🌓 First Quarter"
        elif phase < 0.4375:
            name = "🌔 Waxing Gibbous"
        elif phase < 0.5625:
            name = "🌕 Full Moon"
        elif phase < 0.6875:
            name = "🌖 Waning Gibbous"
        elif phase < 0.8125:
            name = "🌗 Last Quarter"
        else:
            name = "🌘 Waning Crescent"
        self.result.config(text=f"{d.isoformat()}\n{name}\nPhase: {phase*100:.1f}%")

if __name__ == "__main__":
    app = MoonApp()
    # Run headless-friendly: print phase for today then exit after 1s
    app.after(1000, app.destroy)
    app.mainloop()
    print("MoonApp ran OK")
