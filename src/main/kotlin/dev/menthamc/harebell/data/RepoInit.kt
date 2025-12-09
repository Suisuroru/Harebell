package dev.menthamc.harebell.data

class RepoInit {
    fun init(): RepoTarget {
        while (true) {
            val repos = BuildInRepo.entries.toTypedArray()

            println("请选择要下载的仓库 / Please select a repository to download:")
            repos.forEachIndexed { index, buildInRepo ->
                val target = buildInRepo.repoTarget
                println("${index + 1}. ${buildInRepo.name} (${target.owner}/${target.repo})")
            }
            println("${repos.size + 1}. 自定义仓库 / Custom repository")

            print("请输入选项编号 / Please enter the option number: ")
            val input = readlnOrNull()?.toIntOrNull()

            when {
                input != null && input in 1..repos.size -> {
                    return repos[input - 1].repoTarget
                }

                input == repos.size + 1 -> {
                    val customRepo = createCustomRepoTarget()
                    if (customRepo != null) {
                        return customRepo
                    }
                }

                else -> {
                    println("无效的选择 / Invalid selection")
                }
            }
        }
    }

    private fun createCustomRepoTarget(): RepoTarget? {
        print("请输入仓库所有者 (owner) / Please enter repository owner: ")
        val owner = readlnOrNull()?.trim()

        print("请输入仓库名称 (repo) / Please enter repository name: ")
        val repo = readlnOrNull()?.trim()

        if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
            println("所有者和仓库名称不能为空 / Owner and repository name cannot be empty")
            return null
        }

        println("确认信息 / Confirm information:")
        println("仓库所有者 / Repository owner: $owner")
        println("仓库名称 / Repository name: $repo")
        print("是否确认？(Y/N) / Confirm? (Y/N): ")

        val confirm = readlnOrNull()?.trim()?.lowercase()
        return if (confirm == "y" || confirm == "yes") {
            RepoTarget(owner, repo)
        } else {
            println("操作已取消 / Operation cancelled")
            null
        }
    }
}