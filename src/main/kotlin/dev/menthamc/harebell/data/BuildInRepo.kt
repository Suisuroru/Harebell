package dev.menthamc.harebell.data

enum class BuildInRepo(val repoTarget: RepoTarget) {
    Mint(RepoTarget("MenthaMC", "Mint")),
    Luminol(RepoTarget("LuminolMC", "Luminol")),
    LightingLuminol(RepoTarget("LuminolMC", "LightingLuminol")),
    Lophine(RepoTarget("LuminolMC", "Lophine")),
    Leaves(RepoTarget("LeavesMC", "Leaves")),
    Leaf(RepoTarget("Winds-Studio", "Leaf")),
    Paper(RepoTarget("PaperMC", "Paper")),
    Folia(RepoTarget("PaperMC", "Folia")),
    Velocity(RepoTarget("PaperMC", "Velocity")),
}
