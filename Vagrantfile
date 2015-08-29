# -*- mode: ruby -*-
# vi: set ft=ruby :

VAGRANTFILE_API_VERSION = "2"

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  config.vm.box = "ubuntu/trusty64"
  config.vm.synced_folder ".", "/vagrant"
  config.vm.define "cd" do |d|
    d.vm.hostname = "cd"
    d.vm.network "private_network", ip: "10.100.198.200"
    d.vm.provision :shell, path: "scripts/bootstrap_ansible.sh"
    d.vm.provision :shell, inline: "ansible-playbook /vagrant/ansible/cd.yml -c local -v"
    d.vm.provider "virtualbox" do |v|
      v.memory = 1024
    end
  end
  config.vm.define "prod" do |d|
    d.vm.hostname = "prod"
    d.vm.network "private_network", ip: "10.100.198.201"
    d.vm.provider "virtualbox" do |v|
      v.memory = 1024
    end
  end
  (1..3).each do |i|
    config.vm.define "serv-disc-0#{i}" do |d|
      d.vm.hostname = "serv-disc-0#{i}"
      d.vm.network "private_network", ip: "10.100.197.20#{i}"
      d.vm.provider "virtualbox" do |v|
        v.memory = 1024
      end
    end
  end
  if Vagrant.has_plugin?("vagrant-cachier")
    config.cache.scope = :box
  end
end