const TARGET_RATE = 16_000;
const FRAME_SAMPLES = 320;

class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / TARGET_RATE;
    this.remaining = this.ratio;
    this.total = 0;
    this.frame = new ArrayBuffer(FRAME_SAMPLES * 2);
    this.view = new DataView(this.frame);
    this.offset = 0;
    this.energy = 0;
    this.port.onmessage = event => {
      if (event.data === "flush") {
        if (this.offset) this.emit();
        this.port.postMessage({ flushed: true });
      }
    };
  }

  process(inputs) {
    const input = inputs[0];
    const channelCount = input.length;
    if (!channelCount) return true;

    for (let index = 0; index < input[0].length; index++) {
      let mono = 0;
      for (const channel of input) mono += channel[index];
      mono /= channelCount;

      let available = 1;
      while (available > 1e-10) {
        const take = Math.min(this.remaining, available);
        this.total += mono * take;
        this.remaining -= take;
        available -= take;
        if (this.remaining <= 1e-10) {
          this.write(this.total / this.ratio);
          this.remaining = this.ratio;
          this.total = 0;
        }
      }
    }
    return true;
  }

  write(sample) {
    const clipped = Math.max(-1, Math.min(1, sample));
    const pcm = Math.round(clipped < 0 ? clipped * 32768 : clipped * 32767);
    this.view.setInt16(this.offset * 2, pcm, true);
    this.offset++;
    this.energy += clipped * clipped;
    if (this.offset === FRAME_SAMPLES) this.emit();
  }

  emit() {
    const pcm = this.offset === FRAME_SAMPLES ? this.frame : this.frame.slice(0, this.offset * 2);
    const level = Math.min(1, Math.sqrt(this.energy / this.offset) * 3);
    this.port.postMessage({ pcm, level }, [pcm]);
    this.frame = new ArrayBuffer(FRAME_SAMPLES * 2);
    this.view = new DataView(this.frame);
    this.offset = 0;
    this.energy = 0;
  }
}

registerProcessor("captionbridge-pcm", PcmCapture);
