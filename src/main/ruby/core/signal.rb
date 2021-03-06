# Copyright (c) 2007-2015, Evan Phoenix and contributors
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
# * Redistributions of source code must retain the above copyright notice, this
#   list of conditions and the following disclaimer.
# * Redistributions in binary form must reproduce the above copyright notice
#   this list of conditions and the following disclaimer in the documentation
#   and/or other materials provided with the distribution.
# * Neither the name of Rubinius nor the names of its contributors
#   may be used to endorse or promote products derived from this software
#   without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
# OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

module Signal
  Names = {
    'EXIT' => 0
  }

  Numbers = {
    0 => 'EXIT'
  }

  NSIG = Truffle::Config['platform.limits.NSIG']

  # Fill the Names and Numbers Hash.
  prefix = 'platform.signal.'
  Truffle::Config.section(prefix) do |name, number|
    name = name[prefix.size+3..-1]
    Names[name] = number
    Numbers[number] = name
  end

  # Define CLD as CHLD if it's not defined by the platform
  unless Names.key? 'CLD'
    Names['CLD'] = Names['CHLD']
  end

  # Signal.signame(number) always returns the original and not the alias when they have the same signal numbers
  # for CLD => CHLD and IOT => ABRT.
  # CLD and IOT is not always recognized by `new sun.misc.Signal(name)` (IOT is known on linux).
  Numbers[Names['CHLD']] = 'CHLD'
  Numbers[Names['ABRT']] = 'ABRT'

  @threads = {}
  @handlers = {}

  def self.trap(sig, prc=nil, &block)
    sig = sig.to_s if sig.kind_of?(Symbol)

    if sig.kind_of?(String)
      osig = sig

      if sig.start_with? 'SIG'
        sig = sig[3..-1]
      end

      unless number = Names[sig]
        raise ArgumentError, "Unknown signal '#{osig}'"
      end
    else
      number = Truffle::Type.coerce_to_int sig
    end

    signame = self.signame(number)

    if signame == 'VTALRM'
      # Used internally to unblock native calls, like MRI
      raise ArgumentError, "can't trap reserved signal: SIGVTALRM"
    end

    # If no command, use the block.
    prc ||= block
    prc = prc.to_s if prc.kind_of?(Symbol)

    case prc
    when 'DEFAULT', 'SIG_DFL'
      had_old = @handlers.key?(number)
      old = @handlers.delete(number)

      if number != Names['EXIT']
        unless Truffle.invoke_primitive :vm_watch_signal, signame, 'DEFAULT'
          return 'SYSTEM_DEFAULT'
        end
      end

      return 'DEFAULT' unless had_old
      return old ? old : nil
    when 'IGNORE', 'SIG_IGN'
      prc = 'IGNORE'
    when nil
      prc = nil
    when 'EXIT'
      prc = proc { exit }
    when String
      raise ArgumentError, "Unsupported command '#{prc}'"
    else
      unless prc.respond_to? :call
        raise ArgumentError, "Handler must respond to #call (was #{prc.class})"
      end
    end

    had_old = @handlers.key?(number)

    old = @handlers[number]
    @handlers[number] = prc

    if number != Names['EXIT']
      handler = (prc.nil? || prc == 'IGNORE') ? nil : prc
      Truffle.invoke_primitive :vm_watch_signal, signame, handler
    end

    return 'DEFAULT' unless had_old
    old ? old : nil
  end

  def self.list
    Names.dup
  end

  def self.signame(signo)
    index = Truffle::Type.coerce_to_int signo

    Numbers[index]
  end
end
